package com.vaultflow.metadata.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LifecycleScheduler {

  private final MetadataService metadataService;

  @Scheduled(cron = "0 0 * * * *") // top of every hour
  public void applyLifecycleRules() {
    log.info("Running lifecycle rule enforcement...");
    int expired = metadataService.applyExpirationRules();
    log.info("Lifecycle enforcement complete: {} objects expired", expired);
  }
}
