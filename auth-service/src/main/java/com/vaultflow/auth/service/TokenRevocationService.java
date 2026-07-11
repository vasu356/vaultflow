package com.vaultflow.auth.service;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Access token revocation via Redis blacklist.
 *
 * <p>Problem: JWT access tokens are stateless by design. Once issued, they're valid until expiry
 * even after logout. For a 15-minute access token, a stolen token could be used for up to 15
 * minutes after logout.
 *
 * <p>Solution: On logout, store the token's JTI (unique token ID) in Redis with TTL matching the
 * token's remaining lifetime. On every authenticated request, the JWT filter checks the blacklist.
 * If the JTI is present → reject the token.
 *
 * <p>Why Redis (not DB)? This is a hot path — every authenticated request checks the blacklist.
 * Redis O(1) GET vs DB index lookup. Redis TTL auto-expires entries — no cleanup job needed.
 *
 * <p>Trade-off: This adds a Redis round-trip to every authenticated request. Mitigated by: 1. Redis
 * Cluster with read replicas handles millions of reads/sec 2. Local Caffeine L1 cache can front
 * Redis for ultra-hot JTIs (logout-all-devices scenario) 3. The alternative (session-based tokens)
 * is worse for scalability
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenRevocationService {

  private static final String BLACKLIST_PREFIX = "revoked:jti:";

  private final StringRedisTemplate redisTemplate;

  /**
   * Add a JWT ID to the blacklist with the given TTL (should match remaining token validity). After
   * TTL, the key auto-expires — no cleanup needed.
   */
  public void blacklist(String jti, long ttlSeconds) {
    String key = BLACKLIST_PREFIX + jti;
    redisTemplate.opsForValue().set(key, "1", ttlSeconds, TimeUnit.SECONDS);
    log.debug("Blacklisted JTI: {} for {}s", jti, ttlSeconds);
  }

  /**
   * Check if a JWT ID has been revoked. Called by JwtAuthenticationFilter on every request. Redis
   * GET is O(1) — negligible overhead.
   */
  public boolean isRevoked(String jti) {
    String key = BLACKLIST_PREFIX + jti;
    Boolean exists = redisTemplate.hasKey(key);
    return Boolean.TRUE.equals(exists);
  }
}
