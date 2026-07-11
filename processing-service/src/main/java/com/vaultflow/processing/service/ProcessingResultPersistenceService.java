package com.vaultflow.processing.service;

import com.vaultflow.common.event.FileProcessedEvent;
import com.vaultflow.common.event.FileProcessedEvent.ProcessingType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists processing results to the object_versions table.
 *
 * <p>Uses JdbcTemplate for direct updates rather than JPA to avoid loading the full entity.
 * Processing results are updates to specific columns — a full ORM entity load+save is wasteful.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProcessingResultPersistenceService {

  private final JdbcTemplate jdbc;

  @Transactional
  public void persistResults(String objectVersionId, List<FileProcessedEvent> results) {
    UUID versionId = UUID.fromString(objectVersionId);

    for (FileProcessedEvent result : results) {
      try {
        applyResult(versionId, result);
      } catch (Exception e) {
        log.error(
            "Failed to persist processing result: type={} versionId={} error={}",
            result.processingType(),
            objectVersionId,
            e.getMessage(),
            e);
      }
    }

    // Update overall processing status
    boolean allComplete =
        results.stream().allMatch(r -> r.status() != FileProcessedEvent.ProcessingStatus.FAILED);
    boolean anyFailed =
        results.stream().anyMatch(r -> r.status() == FileProcessedEvent.ProcessingStatus.FAILED);

    String overallStatus = anyFailed ? "FAILED" : "COMPLETED";
    jdbc.update(
        "UPDATE object_versions SET processing_status = ? WHERE id = ?::uuid",
        overallStatus,
        objectVersionId);

    log.info(
        "Processing results persisted: versionId={} status={}", objectVersionId, overallStatus);
  }

  private void applyResult(UUID versionId, FileProcessedEvent result) {
    if (result.processingType() == ProcessingType.VIRUS_SCAN) {
      String scanStatus = result.resultMetadata().getOrDefault("status", "ERROR");
      jdbc.update(
          "UPDATE object_versions SET virus_scan_status = ?, virus_scan_at = ? WHERE id = ?::uuid",
          scanStatus,
          Timestamp.from(Instant.now()),
          versionId.toString());

    } else if (result.processingType() == ProcessingType.IMAGE_THUMBNAIL) {
      String thumbnailKey = result.resultMetadata().get("thumbnailKey");
      if (thumbnailKey != null) {
        jdbc.update(
            "UPDATE object_versions SET thumbnail_key = ? WHERE id = ?::uuid",
            thumbnailKey,
            versionId.toString());
      }

    } else if (result.processingType() == ProcessingType.VIDEO_THUMBNAIL) {
      String thumbnailKey = result.resultMetadata().get("thumbnailKey");
      if (thumbnailKey != null) {
        jdbc.update(
            "UPDATE object_versions SET thumbnail_key = ? WHERE id = ?::uuid",
            thumbnailKey,
            versionId.toString());
      }

    } else if (result.processingType() == ProcessingType.PDF_PREVIEW) {
      String previewKey = result.resultMetadata().get("previewKey");
      if (previewKey != null) {
        jdbc.update(
            "UPDATE object_versions SET preview_key = ? WHERE id = ?::uuid",
            previewKey,
            versionId.toString());
      }
    }
  }
}
