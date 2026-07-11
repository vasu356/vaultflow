package com.vaultflow.upload.service;

import com.vaultflow.upload.domain.repository.UploadSessionRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodic job to expire stale upload sessions. Sessions that were initiated or in-progress but
 * never completed within 24 hours are marked EXPIRED. Their parts are cleaned up separately by a
 * storage GC job.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UploadSessionExpiryScheduler {

  private final UploadSessionRepository sessionRepository;

  @Scheduled(fixedDelay = 300_000) // every 5 minutes
  @Transactional
  public void expireStaleUploadSessions() {
    int expired = sessionRepository.expireStaleSessions(Instant.now());
    if (expired > 0) {
      log.info("Expired {} stale upload sessions", expired);
    }
  }
}
