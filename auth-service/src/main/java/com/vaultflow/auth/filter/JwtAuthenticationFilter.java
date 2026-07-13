package com.vaultflow.auth.filter;

import com.vaultflow.auth.service.TokenRevocationService;
import com.vaultflow.common.security.JwtTokenProvider;
import com.vaultflow.common.security.VaultFlowUserPrincipal;
import com.vaultflow.common.tracing.CorrelationIdFilter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtTokenProvider jwtTokenProvider;
  private final TokenRevocationService revocationService;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String token = extractToken(request);

    if (token != null) {
      try {
        Claims claims = jwtTokenProvider.validateAndExtract(token);

        // Reject revoked tokens (post-logout blacklist)
        String jti = claims.getId();
        if (jti != null && revocationService.isRevoked(jti)) {
          log.debug("Rejected revoked token jti={}", jti);
          chain.doFilter(request, response);
          return;
        }

        // Only access tokens are accepted for API authentication
        if (!jwtTokenProvider.isAccessToken(claims)) {
          log.debug("Rejected non-access token type");
          chain.doFilter(request, response);
          return;
        }

        @SuppressWarnings("unchecked")
        List<String> scopes = claims.get(JwtTokenProvider.CLAIM_SCOPES, List.class);

        String correlationId = MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID);

        VaultFlowUserPrincipal principal =
            new VaultFlowUserPrincipal(
                claims.getSubject(),
                claims.get(JwtTokenProvider.CLAIM_EMAIL, String.class),
                claims.get(JwtTokenProvider.CLAIM_ORG_ID, String.class),
                claims.get(JwtTokenProvider.CLAIM_ROLE, String.class),
                scopes != null ? scopes : List.of(),
                correlationId);

        // Populate MDC for structured logging context on all subsequent log calls
        MDC.put(CorrelationIdFilter.MDC_USER_ID, principal.userId());
        MDC.put(CorrelationIdFilter.MDC_ORG_ID, principal.orgId());

        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);

      } catch (JwtException e) {
        log.debug("JWT validation failed: {}", e.getMessage());
        // Don't set auth context — Spring Security will return 401 for protected endpoints
      }
    }

    chain.doFilter(request, response);
  }

  private String extractToken(HttpServletRequest request) {
    String header = request.getHeader(AUTHORIZATION_HEADER);
    if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
      return header.substring(BEARER_PREFIX.length());
    }
    return null;
  }
}
