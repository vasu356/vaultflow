package com.vaultflow.auth.service;

import com.vaultflow.auth.domain.entity.Organization;
import com.vaultflow.auth.domain.entity.RefreshToken;
import com.vaultflow.auth.domain.entity.User;
import com.vaultflow.auth.domain.entity.User.UserStatus;
import com.vaultflow.auth.domain.enums.UserRole;
import com.vaultflow.auth.domain.repository.OrganizationRepository;
import com.vaultflow.auth.domain.repository.RefreshTokenRepository;
import com.vaultflow.auth.domain.repository.UserRepository;
import com.vaultflow.auth.dto.request.AuthRequests.LoginRequest;
import com.vaultflow.auth.dto.request.AuthRequests.RefreshTokenRequest;
import com.vaultflow.auth.dto.request.AuthRequests.RegisterOrganizationRequest;
import com.vaultflow.auth.dto.response.AuthResponses.TokenResponse;
import com.vaultflow.auth.dto.response.AuthResponses.UserResponse;
import com.vaultflow.common.exception.ConflictException;
import com.vaultflow.common.exception.VaultFlowException;
import com.vaultflow.common.security.JwtTokenProvider;
import com.vaultflow.common.util.ChecksumUtil;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core authentication service. Owns: - Organization + user registration (atomic: org + user in one
 * transaction) - Login with brute-force protection - JWT access + refresh token issuance - Token
 * rotation with theft detection - Logout (single device) and logout-all-devices
 *
 * <p>Design: We prefer @Transactional at service layer (not repository layer) to ensure atomicity
 * spans multiple repository operations. The registration flow creates org + user + emits audit
 * event — all or nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

  private final OrganizationRepository orgRepository;
  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenProvider jwtTokenProvider;
  private final TokenRevocationService revocationService;
  private final MeterRegistry meterRegistry;

  private static final long ACCESS_TOKEN_EXPIRY_SECONDS = 900L; // 15 minutes

  // ============================================================
  // Registration
  // ============================================================

  /**
   * Register a new organization and its first user (OWNER role). Atomic — if any step fails, the
   * entire registration is rolled back.
   */
  @Transactional
  public TokenResponse registerOrganization(RegisterOrganizationRequest req, String ipAddress) {
    log.info("Registering organization: slug={} email={}", req.organizationSlug(), req.email());

    if (orgRepository.existsBySlug(req.organizationSlug())) {
      throw new ConflictException("Organization slug already taken: " + req.organizationSlug());
    }

    Organization org =
        Organization.builder().name(req.organizationName()).slug(req.organizationSlug()).build();
    org = orgRepository.save(org);

    User owner =
        User.builder()
            .organization(org)
            .email(req.email().toLowerCase())
            .passwordHash(passwordEncoder.encode(req.password()))
            .fullName(req.fullName())
            .role(UserRole.OWNER)
            .status(UserStatus.ACTIVE)
            .emailVerified(false)
            .build();
    owner = userRepository.save(owner);

    meterRegistry.counter("auth.registrations.total").increment();
    log.info("Organization registered: orgId={} userId={}", org.getId(), owner.getId());

    return issueTokens(owner, ipAddress);
  }

  // ============================================================
  // Login
  // ============================================================

  @Transactional
  public TokenResponse login(LoginRequest req, String ipAddress) {
    User user =
        userRepository
            .findActiveByEmail(req.email().toLowerCase(), UserStatus.ACTIVE)
            .orElseThrow(
                () -> {
                  meterRegistry
                      .counter("auth.login.failures", "reason", "user_not_found")
                      .increment();
                  // Return same error as bad password to prevent user enumeration
                  return new VaultFlowException(
                      "Invalid credentials", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
                });

    if (user.isLocked()) {
      meterRegistry.counter("auth.login.failures", "reason", "account_locked").increment();
      throw new VaultFlowException(
          "Account temporarily locked due to too many failed attempts. Try again later.",
          HttpStatus.TOO_MANY_REQUESTS,
          "ACCOUNT_LOCKED");
    }

    if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
      user.recordFailedLogin();
      userRepository.save(user);
      meterRegistry.counter("auth.login.failures", "reason", "bad_password").increment();
      log.warn(
          "Failed login attempt for email={} ip={} failCount={}",
          req.email(),
          ipAddress,
          user.getFailedLoginCount());
      throw new VaultFlowException(
          "Invalid credentials", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
    }

    user.recordSuccessfulLogin(ipAddress);
    userRepository.save(user);

    meterRegistry.counter("auth.login.successes").increment();
    log.info("User logged in: userId={} email={}", user.getId(), user.getEmail());

    return issueTokens(user, ipAddress);
  }

  // ============================================================
  // Token Refresh with rotation
  // ============================================================

  /**
   * Refresh token rotation strategy:
   *
   * <ol>
   *   <li>Validate the incoming refresh token
   *   <li>If already revoked → revoke entire family (theft detection) → 401
   *   <li>If valid → revoke old token, issue new access + refresh token pair
   *   <li>New refresh token inherits same family_id (enables later theft detection)
   * </ol>
   *
   * <p>Why revoke the old token rather than keeping it? Rotation limits the window of token reuse
   * after a leak. If an attacker captures a refresh token and uses it after the legitimate user has
   * already rotated, we detect the reuse and invalidate the entire family.
   */
  @Transactional
  public TokenResponse refreshToken(RefreshTokenRequest req, String ipAddress) {
    String tokenHash = ChecksumUtil.sha256Hex(req.refreshToken());

    RefreshToken storedToken =
        refreshTokenRepository
            .findByTokenHash(tokenHash)
            .orElseThrow(
                () ->
                    new VaultFlowException(
                        "Invalid refresh token", HttpStatus.UNAUTHORIZED, "INVALID_TOKEN"));

    if (storedToken.getRevoked()) {
      // Token reuse detected — revoke entire family
      log.warn(
          "Refresh token reuse detected! familyId={} userId={}. Revoking family.",
          storedToken.getFamilyId(),
          storedToken.getUser().getId());
      refreshTokenRepository.revokeFamily(
          storedToken.getFamilyId(), Instant.now(), "TOKEN_REUSE_DETECTED");
      meterRegistry.counter("auth.token.theft_detected").increment();
      throw new VaultFlowException(
          "Token has already been used. Please log in again.",
          HttpStatus.UNAUTHORIZED,
          "TOKEN_REUSE");
    }

    if (storedToken.isExpired()) {
      throw new VaultFlowException(
          "Refresh token expired", HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED");
    }

    // Revoke old token and issue new pair
    storedToken.revoke("ROTATED");
    refreshTokenRepository.save(storedToken);

    User user = storedToken.getUser();
    return issueTokens(user, ipAddress, storedToken.getFamilyId());
  }

  // ============================================================
  // Logout
  // ============================================================

  @Transactional
  public void logout(String refreshToken, String accessTokenJti) {
    String tokenHash = ChecksumUtil.sha256Hex(refreshToken);
    refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(t -> t.revoke("LOGOUT"));

    // Blacklist access token JTI in Redis until it naturally expires
    if (accessTokenJti != null) {
      revocationService.blacklist(accessTokenJti, ACCESS_TOKEN_EXPIRY_SECONDS);
    }
    meterRegistry.counter("auth.logouts.total").increment();
  }

  @Transactional
  public void logoutAllDevices(UUID userId) {
    refreshTokenRepository.revokeAllForUser(userId, Instant.now());
    log.info("All refresh tokens revoked for userId={}", userId);
    meterRegistry.counter("auth.logouts.all_devices").increment();
  }

  // ============================================================
  // Token issuance (private)
  // ============================================================

  private TokenResponse issueTokens(User user, String ipAddress) {
    return issueTokens(user, ipAddress, UUID.randomUUID());
  }

  private TokenResponse issueTokens(User user, String ipAddress, UUID familyId) {
    Organization org = user.getOrganization();
    List<String> scopes = scopesForRole(user.getRole());

    String accessToken =
        jwtTokenProvider.generateAccessToken(
            user.getId().toString(),
            user.getEmail(),
            org.getId().toString(),
            user.getRole().name(),
            scopes);

    String rawRefreshToken = UUID.randomUUID().toString();
    String refreshTokenHash = ChecksumUtil.sha256Hex(rawRefreshToken);

    RefreshToken refreshToken =
        RefreshToken.builder()
            .user(user)
            .tokenHash(refreshTokenHash)
            .familyId(familyId)
            .ipAddress(ipAddress)
            .expiresAt(Instant.now().plusSeconds(7 * 24 * 3600)) // 7 days
            .build();
    refreshTokenRepository.save(refreshToken);

    UserResponse userResponse =
        new UserResponse(
            user.getId(),
            user.getEmail(),
            user.getFullName(),
            user.getRole().name(),
            org.getId(),
            org.getSlug(),
            org.getName(),
            user.getEmailVerified(),
            user.getCreatedAt(),
            user.getLastLoginAt());

    return TokenResponse.of(
        accessToken, rawRefreshToken, ACCESS_TOKEN_EXPIRY_SECONDS, userResponse);
  }

  private List<String> scopesForRole(UserRole role) {
    return switch (role) {
      case OWNER, ADMIN -> List.of("read", "write", "delete", "admin");
      case EDITOR -> List.of("read", "write", "delete");
      case VIEWER -> List.of("read");
    };
  }
}
