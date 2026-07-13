package com.vaultflow.upload.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

public final class UploadResponses {
  private UploadResponses() {}

  @Builder
  public record UploadResponse(
      UUID objectId,
      UUID versionId,
      String objectKey,
      String storageKey,
      long sizeBytes,
      String checksumSha256,
      String etag,
      String contentType,
      boolean isDuplicate,
      Instant uploadedAt) {}

  @Builder
  public record InitiateUploadResponse(
      UUID sessionId, String objectKey, UUID bucketId, Instant expiresAt) {}

  @Builder
  public record PartUploadResponse(
      UUID sessionId,
      int partNumber,
      long sizeBytes,
      String checksumSha256,
      String etag,
      long receivedPartsCount) {}

  @Builder
  public record UploadStatusResponse(
      UUID sessionId,
      String status,
      String objectKey,
      UUID bucketId,
      Integer totalParts,
      int receivedParts,
      List<Integer> receivedPartNumbers,
      Instant expiresAt) {}

  @Builder
  public record BucketResponse(
      UUID id,
      UUID orgId,
      String name,
      String region,
      boolean versioningEnabled,
      String status,
      Instant createdAt) {}

  @Builder
  public record ObjectResponse(
      UUID id,
      UUID bucketId,
      String objectKey,
      String contentType,
      UUID currentVersionId,
      boolean isDeleted,
      Instant createdAt) {}

  @Builder
  public record SignedUrlResponse(
      UUID id,
      String url,
      String token,
      Instant expiresAt,
      Integer maxDownloads,
      Instant createdAt) {}
}
