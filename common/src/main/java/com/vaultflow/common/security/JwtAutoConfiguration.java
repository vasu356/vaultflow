package com.vaultflow.common.security;

import com.vaultflow.common.tracing.CorrelationIdFilter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Auto-configuration providing JWT validation beans to ALL services.
 *
 * <p>auth-service overrides JwtTokenProvider with its own @Bean (has the private key for signing).
 * All other services use the beans defined here — they load only the public key for validation.
 *
 * <p>The filter bean is only created in SERVLET web application contexts — pure Kafka consumer
 * services (processing, notification) don't need it even though they have spring-web on the
 * classpath for actuator endpoints.
 *
 * <p>Important: This configuration NEVER generates RSA keys. It loads the public key from a shared
 * PEM file. The private key lives exclusively in auth-service.
 */
@Configuration
public class JwtAutoConfiguration {

  @Value("${vaultflow.jwt.access-token-expiry-seconds:900}")
  private long accessTokenExpirySeconds;

  @Value("${vaultflow.jwt.refresh-token-expiry-seconds:604800}")
  private long refreshTokenExpirySeconds;

  @Value("${vaultflow.jwt.issuer:vaultflow-auth}")
  private String issuer;

  @Value("${vaultflow.jwt.public-key-path:/keys/public.pem}")
  private String publicKeyPath;

  @Bean
  @ConditionalOnMissingBean(JwtTokenProvider.class)
  public JwtTokenProvider jwtTokenProvider() {
    try {
      PublicKey publicKey = PemUtil.loadPublicKey(Path.of(publicKeyPath));
      return new JwtTokenProvider(
          publicKey, accessTokenExpirySeconds, refreshTokenExpirySeconds, issuer);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to load JWT public key from "
              + publicKeyPath
              + ". Ensure the keys/ directory is mounted at /keys in the container "
              + "and contains public.pem.",
          e);
    }
  }

  /**
   * JWT filter for services that expose HTTP APIs (upload, download, admin, metadata). NOT created
   * for processing-service or notification-service (pure consumers). NOT created if auth-service
   * provides its own (ConditionalOnMissingBean).
   */
  @Bean("jwtAuthFilter")
  @ConditionalOnMissingBean(name = "jwtAuthFilter")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  public OncePerRequestFilter jwtAuthFilter(JwtTokenProvider jwtTokenProvider) {
    return new OncePerRequestFilter() {
      private static final String AUTHORIZATION_HEADER = "Authorization";
      private static final String BEARER_PREFIX = "Bearer ";

      @Override
      protected void doFilterInternal(
          HttpServletRequest request, HttpServletResponse response, FilterChain chain)
          throws ServletException, IOException {

        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
          String token = header.substring(BEARER_PREFIX.length());
          try {
            Claims claims = jwtTokenProvider.validateAndExtract(token);
            if (jwtTokenProvider.isAccessToken(claims)) {
              @SuppressWarnings("unchecked")
              List<String> scopes = claims.get(JwtTokenProvider.CLAIM_SCOPES, List.class);

              VaultFlowUserPrincipal principal =
                  new VaultFlowUserPrincipal(
                      claims.getSubject(),
                      claims.get(JwtTokenProvider.CLAIM_EMAIL, String.class),
                      claims.get(JwtTokenProvider.CLAIM_ORG_ID, String.class),
                      claims.get(JwtTokenProvider.CLAIM_ROLE, String.class),
                      scopes != null ? scopes : List.of(),
                      MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID));

              MDC.put(CorrelationIdFilter.MDC_USER_ID, principal.userId());
              MDC.put(CorrelationIdFilter.MDC_ORG_ID, principal.orgId());

              UsernamePasswordAuthenticationToken auth =
                  new UsernamePasswordAuthenticationToken(
                      principal, null, principal.getAuthorities());
              auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
              SecurityContextHolder.getContext().setAuthentication(auth);
            }
          } catch (JwtException ignored) {
            // Invalid token — leave security context empty; Spring Security handles 401
          }
        }
        chain.doFilter(request, response);
      }
    };
  }
}
