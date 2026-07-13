package com.vaultflow.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RS256 JWT provider. We use asymmetric signing (RSA-256) rather than HMAC-256 because:
 *
 * <ul>
 *   <li>Each service can validate tokens using only the public key — no shared secret distribution
 *   <li>The private key lives exclusively in auth-service — compromise of other services does not
 *       enable token forgery
 *   <li>Public key can be exposed via JWKS endpoint for third-party integration
 * </ul>
 *
 * <p>Token structure:
 *
 * <pre>
 * Header: { alg: RS256, kid: keyId }
 * Payload: {
 *   sub:    userId,
 *   email:  user@example.com,
 *   orgId:  org UUID,
 *   role:   OWNER|ADMIN|EDITOR|VIEWER,
 *   scopes: [read, write, delete, admin],
 *   iat:    issued-at,
 *   exp:    expiry (15 min for access, 7 days for refresh),
 *   jti:    unique token ID (for revocation blacklist)
 * }
 * </pre>
 *
 * <p>Resource services (upload, download, admin, metadata) create this provider with {@code
 * privateKey = null} — they can validate tokens but cannot sign new ones. Only auth-service has the
 * private key and can sign tokens.
 */
public class JwtTokenProvider {

  private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

  public static final String CLAIM_EMAIL = "email";
  public static final String CLAIM_ORG_ID = "orgId";
  public static final String CLAIM_ROLE = "role";
  public static final String CLAIM_SCOPES = "scopes";
  public static final String CLAIM_TOKEN_TYPE = "tokenType";
  public static final String TOKEN_TYPE_ACCESS = "access";
  public static final String TOKEN_TYPE_REFRESH = "refresh";

  private final PrivateKey privateKey;
  final PublicKey publicKey; // package-visible for JWKS endpoint
  private final long accessTokenExpirySeconds;
  private final long refreshTokenExpirySeconds;
  private final String issuer;

  /** Create a JwtTokenProvider with both keys (auth-service — can sign and validate). */
  public JwtTokenProvider(
      PrivateKey privateKey,
      PublicKey publicKey,
      long accessTokenExpirySeconds,
      long refreshTokenExpirySeconds,
      String issuer) {
    this.privateKey = privateKey;
    this.publicKey = publicKey;
    this.accessTokenExpirySeconds = accessTokenExpirySeconds;
    this.refreshTokenExpirySeconds = refreshTokenExpirySeconds;
    this.issuer = issuer;
  }

  /** Create a JwtTokenProvider with only a public key (resource services — can validate only). */
  public JwtTokenProvider(
      PublicKey publicKey,
      long accessTokenExpirySeconds,
      long refreshTokenExpirySeconds,
      String issuer) {
    this.privateKey = null;
    this.publicKey = publicKey;
    this.accessTokenExpirySeconds = accessTokenExpirySeconds;
    this.refreshTokenExpirySeconds = refreshTokenExpirySeconds;
    this.issuer = issuer;
  }

  public String generateAccessToken(
      String userId, String email, String orgId, String role, List<String> scopes) {
    return buildToken(
        userId, email, orgId, role, scopes, accessTokenExpirySeconds, TOKEN_TYPE_ACCESS);
  }

  public String generateRefreshToken(String userId, String email, String orgId, String role) {
    return buildToken(
        userId,
        email,
        orgId,
        role,
        List.of("refresh"),
        refreshTokenExpirySeconds,
        TOKEN_TYPE_REFRESH);
  }

  private String buildToken(
      String userId,
      String email,
      String orgId,
      String role,
      List<String> scopes,
      long expirySeconds,
      String tokenType) {

    if (privateKey == null) {
      throw new IllegalStateException(
          "JwtTokenProvider has no private key configured. "
              + "Only auth-service can sign tokens.");
    }

    Instant now = Instant.now();
    return Jwts.builder()
        .subject(userId)
        .issuer(issuer)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(expirySeconds)))
        .id(UUID.randomUUID().toString()) // jti — unique per token, used for revocation
        .claims(
            Map.of(
                CLAIM_EMAIL, email,
                CLAIM_ORG_ID, orgId,
                CLAIM_ROLE, role,
                CLAIM_SCOPES, scopes,
                CLAIM_TOKEN_TYPE, tokenType))
        .signWith(privateKey)
        .compact();
  }

  /**
   * Parse and validate a JWT. Returns claims on success. Throws JwtException subtypes on failure —
   * callers should catch JwtException, not individual subtypes, for forward-compatibility.
   */
  public Claims validateAndExtract(String token) {
    try {
      return Jwts.parser()
          .verifyWith(publicKey)
          .requireIssuer(issuer)
          .build()
          .parseSignedClaims(token)
          .getPayload();
    } catch (ExpiredJwtException e) {
      log.debug("JWT expired: jti={}", e.getClaims().getId());
      throw e;
    } catch (SignatureException e) {
      log.warn("JWT signature verification failed");
      throw e;
    } catch (JwtException e) {
      log.warn("JWT validation failed: {}", e.getMessage());
      throw e;
    }
  }

  public boolean isAccessToken(Claims claims) {
    return TOKEN_TYPE_ACCESS.equals(claims.get(CLAIM_TOKEN_TYPE, String.class));
  }

  public boolean isRefreshToken(Claims claims) {
    return TOKEN_TYPE_REFRESH.equals(claims.get(CLAIM_TOKEN_TYPE, String.class));
  }

  @SuppressWarnings("unchecked")
  public List<String> getScopes(Claims claims) {
    return claims.get(CLAIM_SCOPES, List.class);
  }

  public PublicKey getPublicKey() {
    return publicKey;
  }
}
