package com.vaultflow.processing.processor;

import com.vaultflow.common.event.FileProcessedEvent;
import com.vaultflow.common.event.FileUploadedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Simulated virus scanner.
 *
 * <p>Production integration path: Replace this with ClamAV via unix socket (clamd + jclamav client
 * library) or a cloud AV API (VirusTotal, Metadefender). The interface is identical —
 * ProcessingOrchestrator calls process(event) and receives FileProcessedEvent with CLEAN/INFECTED.
 *
 * <p>Simulation: Detects EICAR test string (industry-standard AV test pattern) in uploaded files.
 * Any file containing the EICAR string is flagged as INFECTED. All others are marked CLEAN.
 *
 * <p>EICAR test string: X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H* Safe
 * to include in test files — it is not a real virus, just a standard test pattern.
 *
 * <p>On INFECTED: The object_versions.virus_scan_status is set to INFECTED. Downloads of infected
 * files are blocked by download-service (checks this status). Admin is notified via
 * notification-service.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VirusScanProcessor {

  private static final byte[] EICAR_PATTERN =
      "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
          .getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  private static final int SCAN_BUFFER_SIZE = 64 * 1024; // 64KB chunks

  private final MeterRegistry meterRegistry;

  @Value("${vaultflow.storage.base-dir:/data/vaultflow/objects}")
  private String storageBaseDir;

  @Value("${vaultflow.processing.virus-scan.max-file-size-bytes:104857600}") // 100MB
  private long maxFileSizeBytes;

  public FileProcessedEvent process(FileUploadedEvent event) {
    try {
      Path sourcePath = Paths.get(storageBaseDir, event.storageKey());

      if (!Files.exists(sourcePath)) {
        return FileProcessedEvent.failed(
            event.objectVersionId(),
            event.objectId(),
            event.bucketId(),
            event.orgId(),
            FileProcessedEvent.ProcessingType.VIRUS_SCAN,
            "Source file not found");
      }

      // Skip scan for files exceeding max size — mark as SKIPPED with warning
      long fileSize = Files.size(sourcePath);
      if (fileSize > maxFileSizeBytes) {
        log.warn(
            "File too large for virus scan, skipping: objectVersionId={} size={}",
            event.objectVersionId(),
            fileSize);
        meterRegistry.counter("processing.virus.skipped").increment();
        return FileProcessedEvent.success(
            event.objectVersionId(),
            event.objectId(),
            event.bucketId(),
            event.orgId(),
            FileProcessedEvent.ProcessingType.VIRUS_SCAN,
            Map.of("status", "SKIPPED", "reason", "FILE_TOO_LARGE"));
      }

      boolean infected = scanForThreats(sourcePath);

      if (infected) {
        log.warn(
            "INFECTED file detected: objectVersionId={} storageKey={}",
            event.objectVersionId(),
            event.storageKey());
        meterRegistry.counter("processing.virus.infected").increment();
        return FileProcessedEvent.success(
            event.objectVersionId(),
            event.objectId(),
            event.bucketId(),
            event.orgId(),
            FileProcessedEvent.ProcessingType.VIRUS_SCAN,
            Map.of("status", "INFECTED", "threat", "EICAR.Test.File"));
      }

      meterRegistry.counter("processing.virus.clean").increment();
      return FileProcessedEvent.success(
          event.objectVersionId(),
          event.objectId(),
          event.bucketId(),
          event.orgId(),
          FileProcessedEvent.ProcessingType.VIRUS_SCAN,
          Map.of("status", "CLEAN"));

    } catch (Exception e) {
      log.error(
          "Virus scan failed: objectVersionId={} error={}",
          event.objectVersionId(),
          e.getMessage(),
          e);
      meterRegistry.counter("processing.virus.errors").increment();
      return FileProcessedEvent.failed(
          event.objectVersionId(),
          event.objectId(),
          event.bucketId(),
          event.orgId(),
          FileProcessedEvent.ProcessingType.VIRUS_SCAN,
          e.getMessage());
    }
  }

  private boolean scanForThreats(Path filePath) throws IOException {
    try (InputStream in =
        new BufferedInputStream(Files.newInputStream(filePath), SCAN_BUFFER_SIZE)) {
      byte[] buffer = new byte[SCAN_BUFFER_SIZE];
      int bytesRead;
      // Sliding window scan for EICAR pattern
      while ((bytesRead = in.read(buffer)) != -1) {
        if (containsPattern(buffer, bytesRead, EICAR_PATTERN)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean containsPattern(byte[] buffer, int length, byte[] pattern) {
    outer:
    for (int i = 0; i <= length - pattern.length; i++) {
      for (int j = 0; j < pattern.length; j++) {
        if (buffer[i + j] != pattern[j]) continue outer;
      }
      return true;
    }
    return false;
  }
}
