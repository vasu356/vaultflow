package com.vaultflow.upload.dto.request;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class UploadRequests {
  private UploadRequests() {}

  public record CreateBucketRequest(
      @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9\\-]{1,61}[a-z0-9]$",
          message = "Bucket name must be 3-63 lowercase alphanumeric chars or hyphens")
      String name,
      String region,
      Boolean versioningEnabled) {}

  public record InitiateUploadRequest(
      @NotNull UUID bucketId,
      @NotBlank @Size(max = 1024) String objectKey,
      String contentType,
      Long expectedSize,
      Integer totalParts) {}

  public record CompleteUploadRequest(List<Integer> partNumbers) {}

  public record UpdateObjectMetadataRequest(
      java.util.Map<String, String> metadata,
      java.util.Map<String, String> tags) {}

  public record CreateSignedUrlRequest(
      @NotNull UUID objectVersionId,
      @Min(60) @Max(604800) long ttlSeconds,
      Integer maxDownloads,
      String allowedIp) {}
}
