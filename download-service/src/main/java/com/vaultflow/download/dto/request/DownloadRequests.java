package com.vaultflow.download.dto.request;

import jakarta.validation.constraints.*;
import java.util.UUID;

public final class DownloadRequests {
  private DownloadRequests() {}

  public record CreateSignedUrlRequest(
      @NotNull UUID objectVersionId,
      @Min(60) @Max(604800) long ttlSeconds,
      @Min(1) Integer maxDownloads,
      String allowedIp) {}
}
