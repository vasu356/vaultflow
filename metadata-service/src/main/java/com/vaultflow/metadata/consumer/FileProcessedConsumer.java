package com.vaultflow.metadata.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vaultflow.common.event.FileProcessedEvent;
import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code file.processed} events and updates the object_versions table.
 *
 * <p>This consumer closes the processing loop: after processing-service completes thumbnail
 * generation and virus scanning, it publishes FileProcessedEvent. This consumer writes the results
 * (thumbnail_key, preview_key, virus_scan_status, processing_status) back to the database row that
 * metadata-service and download-service serve to clients.
 *
 * <p>All updates are idempotent — repeated updates to the same column with the same value are safe.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FileProcessedConsumer {

  private final JdbcTemplate jdbc;

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  @KafkaListener(
      topics = "file.processed",
      groupId = "metadata-service",
      concurrency = "4",
      containerFactory = "metadataKafkaListenerContainerFactory")
  public void consume(ConsumerRecord<String, Object> record, Acknowledgment ack) {
    FileProcessedEvent event = null;
    try {
      event = MAPPER.convertValue(record.value(), FileProcessedEvent.class);
      if (event == null || event.objectVersionId() == null) {
        log.warn("Received null or malformed FileProcessedEvent, skipping");
        ack.acknowledge();
        return;
      }

      MDC.put("objectVersionId", event.objectVersionId());
      MDC.put("orgId", event.orgId());

      applyProcessingResult(event);

      ack.acknowledge();
      log.debug(
          "FileProcessedEvent applied: objectVersionId={} type={} status={}",
          event.objectVersionId(),
          event.processingType(),
          event.status());

    } catch (Exception e) {
      String versionId = event != null ? event.objectVersionId() : "unknown";
      log.error(
          "Failed to apply FileProcessedEvent: objectVersionId={} partition={} offset={}: {}",
          versionId,
          record.partition(),
          record.offset(),
          e.getMessage(),
          e);
      throw new RuntimeException("FileProcessedEvent handling failed", e);
    } finally {
      MDC.clear();
    }
  }

  private void applyProcessingResult(FileProcessedEvent event) {
    String versionId = event.objectVersionId();

    switch (event.processingType()) {
      case VIRUS_SCAN -> {
        String status = event.resultMetadata().getOrDefault("status", "ERROR");
        jdbc.update(
            "UPDATE object_versions SET virus_scan_status = ?, virus_scan_at = ? "
                + "WHERE id = ?::uuid",
            status,
            Timestamp.from(Instant.now()),
            versionId);
        log.debug("virus_scan_status={} applied for versionId={}", status, versionId);
      }
      case IMAGE_THUMBNAIL, VIDEO_THUMBNAIL -> {
        String thumbKey = event.resultMetadata().get("thumbnailKey");
        if (thumbKey != null) {
          jdbc.update(
              "UPDATE object_versions SET thumbnail_key = ? WHERE id = ?::uuid",
              thumbKey,
              versionId);
        }
      }
      case PDF_PREVIEW -> {
        String previewKey = event.resultMetadata().get("previewKey");
        if (previewKey != null) {
          jdbc.update(
              "UPDATE object_versions SET preview_key = ? WHERE id = ?::uuid",
              previewKey,
              versionId);
        }
      }
      case METADATA_EXTRACTION ->
          log.debug(
              "Metadata extraction result received for versionId={}: {}",
              versionId,
              event.resultMetadata());
      default -> log.debug("Unhandled processing type: {}", event.processingType());
    }

    // Re-evaluate the overall processing_status in a single SQL expression.
    // Only updates rows still in PROCESSING — COMPLETED and FAILED are terminal states.
    jdbc.update(
        """
        UPDATE object_versions SET processing_status = CASE
          WHEN virus_scan_status = 'INFECTED' THEN 'FAILED'
          WHEN processing_status = 'FAILED'   THEN 'FAILED'
          ELSE 'COMPLETED'
        END
        WHERE id = ?::uuid
          AND processing_status NOT IN ('COMPLETED', 'FAILED')
        """,
        versionId);
  }
}
