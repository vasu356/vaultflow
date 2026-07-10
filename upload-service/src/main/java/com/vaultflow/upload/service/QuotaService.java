package com.vaultflow.upload.service;

import com.vaultflow.common.exception.QuotaExceededException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Quota enforcement service.
 *
 * <p>Architecture: Quota is checked against a Redis-cached value (TTL 60s) to avoid hitting
 * PostgreSQL on every upload request. The authoritative value lives in the organizations table.
 * Redis serves as a read-through cache — on miss, we query DB and populate cache.
 *
 * <p>Why not always query DB? At 10k concurrent uploads per second, querying organizations for
 * each request creates a hot spot on a single row (the org record). Redis handles this at O(1).
 *
 * <p>Consistency trade-off: Quota check is approximate (up to 60s stale). A burst of concurrent
 * uploads could temporarily exceed quota by the amount arriving within one TTL window. This is
 * acceptable for soft quota enforcement. For hard enforcement (billing), use a DB-level check
 * with SELECT FOR UPDATE before finalizing the upload.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaService {

  private static final String QUOTA_CACHE_PREFIX = "quota:remaining:";
  private static final String USED_CACHE_PREFIX = "quota:used:";
  private static final int CACHE_TTL_SECONDS = 60;

  private final StringRedisTemplate redisTemplate;
  private final JdbcTemplate jdbcTemplate;

  /**
   * Assert that the org has enough remaining quota for the upload. Throws QuotaExceededException
   * if the upload would exceed the quota limit.
   */
  public void assertQuota(UUID orgId, long requiredBytes) {
    long remaining = getRemainingQuota(orgId);
    if (remaining < requiredBytes) {
      long quotaBytes = getQuotaBytes(orgId);
      throw new QuotaExceededException(orgId.toString(), quotaBytes);
    }
  }

  /**
   * Record quota consumption after a successful upload. Updates both DB and invalidates cache so
   * the next check reads fresh values.
   */
  public void consumeQuota(UUID orgId, long bytes) {
    jdbcTemplate.update(
        "UPDATE organizations SET used_bytes = used_bytes + ? WHERE id = ?::uuid",
        bytes, orgId.toString());
    // Invalidate cache — next quota check will re-read from DB
    redisTemplate.delete(QUOTA_CACHE_PREFIX + orgId);
    log.debug("Quota consumed: orgId={} bytes={}", orgId, bytes);
  }

  /**
   * Release quota after a delete. Decrements used_bytes floor at 0.
   */
  public void releaseQuota(UUID orgId, long bytes) {
    jdbcTemplate.update(
        "UPDATE organizations SET used_bytes = GREATEST(0, used_bytes - ?) WHERE id = ?::uuid",
        bytes, orgId.toString());
    redisTemplate.delete(QUOTA_CACHE_PREFIX + orgId);
    log.debug("Quota released: orgId={} bytes={}", orgId, bytes);
  }

  private long getRemainingQuota(UUID orgId) {
    String cacheKey = QUOTA_CACHE_PREFIX + orgId;
    String cached = redisTemplate.opsForValue().get(cacheKey);
    if (cached != null) {
      return Long.parseLong(cached);
    }

    Long remaining = jdbcTemplate.queryForObject(
        "SELECT quota_bytes - used_bytes FROM organizations WHERE id = ?::uuid",
        Long.class, orgId.toString());
    long value = remaining != null ? remaining : 0L;

    redisTemplate.opsForValue().set(cacheKey, String.valueOf(value), CACHE_TTL_SECONDS, TimeUnit.SECONDS);
    return value;
  }

  private long getQuotaBytes(UUID orgId) {
    Long quota = jdbcTemplate.queryForObject(
        "SELECT quota_bytes FROM organizations WHERE id = ?::uuid",
        Long.class, orgId.toString());
    return quota != null ? quota : 0L;
  }
}
