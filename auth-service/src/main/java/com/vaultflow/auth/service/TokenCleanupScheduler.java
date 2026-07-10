package com.vaultflow.auth.service;

import com.vaultflow.auth.domain.repository.RefreshTokenRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled job to remove expired refresh tokens from the database.
 *
 * <p>Why not rely on Redis TTL alone? Refresh tokens are in PostgreSQL for durability and auditability.
 * We keep expired tokens for 30 days after expiry for forensics (token theft investigation),
 * then delete for storage hygiene.
 *
 * <p>Runs nightly at 2 AM. In a multi-pod environment, Spring Scheduling runs on all pods.
 * This is safe because DELETE WHERE expires_at < cutoff is idempotent — duplicate deletes
 * have no effect. For strict single-execution, use a distributed lock (ShedLock pattern).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TokenCleanupScheduler {

  private final RefreshTokenRepository refreshTokenRepository;

  @Scheduled(cron = "0 0 2 * * *") // 2 AM daily
  @Transactional
  public void cleanExpiredTokens() {
    Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
    int deleted = refreshTokenRepository.deleteExpiredBefore(cutoff);
    log.info("Cleaned expired refresh tokens: count={} cutoff={}", deleted, cutoff);
  }
}
