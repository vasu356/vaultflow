package com.vaultflow.download.dto.response;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

public final class DownloadResponses {
  private DownloadResponses() {}

  @Builder
  public record SignedUrlResponse(
      UUID id,
      String url,
      String token,
      Instant expiresAt,
      Integer maxDownloads,
      Instant createdAt) {}

  @Builder
  public record ObjectMetadataResponse(
      UUID versionId,
      UUID objectId,
      String objectKey,
      String contentType,
      long sizeBytes,
      String etag,
      String checksumSha256,
      int versionNumber,
      String processingStatus,
      String virusScanStatus,
      String thumbnailKey,
      Instant createdAt) {}
}
