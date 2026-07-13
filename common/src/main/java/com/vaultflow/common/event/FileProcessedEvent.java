package com.vaultflow.common.event;

import java.time.Instant;
import java.util.Map;

/**
 * Published to Kafka topic {@code file.processed} after processing pipeline completes for an object
 * version. Consumed by notification-service (webhooks) and metadata-service (update processing
 * status and thumbnail paths).
 */
public record FileProcessedEvent(
    String eventId,
    int schemaVersion,
    String objectVersionId,
    String objectId,
    String bucketId,
    String orgId,
    ProcessingType processingType,
    ProcessingStatus status,
    Map<String, String> resultMetadata,
    String errorMessage,
    Instant processedAt) {

  public static final int CURRENT_SCHEMA_VERSION = 1;

  public enum ProcessingType {
    IMAGE_THUMBNAIL,
    VIDEO_THUMBNAIL,
    PDF_PREVIEW,
    VIRUS_SCAN,
    METADATA_EXTRACTION,
    COMPRESSION
  }

  public enum ProcessingStatus {
    SUCCESS,
    FAILED,
    SKIPPED
  }

  public static FileProcessedEvent success(
      String objectVersionId,
      String objectId,
      String bucketId,
      String orgId,
      ProcessingType type,
      Map<String, String> resultMetadata) {
    return new FileProcessedEvent(
        java.util.UUID.randomUUID().toString(),
        CURRENT_SCHEMA_VERSION,
        objectVersionId,
        objectId,
        bucketId,
        orgId,
        type,
        ProcessingStatus.SUCCESS,
        resultMetadata,
        null,
        Instant.now());
  }

  public static FileProcessedEvent failed(
      String objectVersionId,
      String objectId,
      String bucketId,
      String orgId,
      ProcessingType type,
      String errorMessage) {
    return new FileProcessedEvent(
        java.util.UUID.randomUUID().toString(),
        CURRENT_SCHEMA_VERSION,
        objectVersionId,
        objectId,
        bucketId,
        orgId,
        type,
        ProcessingStatus.FAILED,
        Map.of(),
        errorMessage,
        Instant.now());
  }
}
