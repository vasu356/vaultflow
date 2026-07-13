package com.vaultflow.common.event;

import java.time.Instant;

/**
 * Published to Kafka topic {@code file.uploaded} after an object is successfully stored.
 *
 * <p>This event triggers the async processing pipeline: thumbnail generation, virus scanning,
 * metadata extraction, and notification fan-out. All processors are idempotent on objectVersionId.
 *
 * <p>Event versioning: {@code schemaVersion} allows processors to handle schema evolution without
 * breaking existing consumers during rolling deployments.
 */
public record FileUploadedEvent(
    String eventId,
    int schemaVersion,
    String objectId,
    String objectVersionId,
    String bucketId,
    String orgId,
    String uploadedByUserId,
    String objectKey,
    String storageKey,
    String contentType,
    long sizeBytes,
    String checksumSha256,
    boolean requiresImageProcessing,
    boolean requiresVideoProcessing,
    boolean requiresDocumentProcessing,
    boolean requiresVirusScan,
    Instant uploadedAt) {

  public static final int CURRENT_SCHEMA_VERSION = 1;

  public static FileUploadedEvent of(
      String objectId,
      String objectVersionId,
      String bucketId,
      String orgId,
      String uploadedByUserId,
      String objectKey,
      String storageKey,
      String contentType,
      long sizeBytes,
      String checksumSha256) {

    boolean isImage = contentType != null && contentType.startsWith("image/");
    boolean isVideo = contentType != null && contentType.startsWith("video/");
    boolean isDocument = contentType != null && contentType.equals("application/pdf");

    return new FileUploadedEvent(
        java.util.UUID.randomUUID().toString(),
        CURRENT_SCHEMA_VERSION,
        objectId,
        objectVersionId,
        bucketId,
        orgId,
        uploadedByUserId,
        objectKey,
        storageKey,
        contentType,
        sizeBytes,
        checksumSha256,
        isImage,
        isVideo,
        isDocument,
        true, // always virus scan
        Instant.now());
  }
}
