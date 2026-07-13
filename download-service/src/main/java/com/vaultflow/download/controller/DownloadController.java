package com.vaultflow.download.controller;

import com.vaultflow.common.security.VaultFlowUserPrincipal;
import com.vaultflow.download.domain.entity.ObjectVersionView;
import com.vaultflow.download.dto.request.DownloadRequests.CreateSignedUrlRequest;
import com.vaultflow.download.dto.response.DownloadResponses.ObjectMetadataResponse;
import com.vaultflow.download.dto.response.DownloadResponses.SignedUrlResponse;
import com.vaultflow.download.service.DownloadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.InputStream;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Download", description = "Object download with range request and signed URL support")
public class DownloadController {

  private final DownloadService downloadService;

  // ============================================================
  // Authenticated download
  // ============================================================

  @GetMapping("/buckets/{bucketId}/objects/{*objectKey}")
  @Operation(summary = "Download an object (supports HTTP Range requests)")
  public ResponseEntity<InputStreamResource> downloadObject(
      @PathVariable("bucketId") UUID bucketId,
      @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal,
      HttpServletRequest request) {

    String objectKey = extractObjectKey(request, "/api/v1/buckets/" + bucketId + "/objects/");
    ObjectVersionView metadata = downloadService.getObjectMetadata(bucketId, objectKey, principal);

    HttpHeaders headers = buildResponseHeaders(metadata);

    if (rangeHeader != null) {
      return handleRangeRequest(bucketId, objectKey, rangeHeader, metadata, headers, principal);
    }

    InputStream stream = downloadService.streamObject(bucketId, objectKey, principal);
    return ResponseEntity.ok()
        .headers(headers)
        .contentLength(metadata.sizeBytes())
        .contentType(parseMediaType(metadata.contentType()))
        .body(new InputStreamResource(stream));
  }

  @RequestMapping(value = "/buckets/{bucketId}/objects/{*objectKey}", method = RequestMethod.HEAD)
  @Operation(summary = "Get object metadata without body")
  public ResponseEntity<Void> headObject(
      @PathVariable("bucketId") UUID bucketId,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal,
      HttpServletRequest request) {

    String objectKey = extractObjectKey(request, "/api/v1/buckets/" + bucketId + "/objects/");
    ObjectVersionView metadata = downloadService.getObjectMetadata(bucketId, objectKey, principal);

    return ResponseEntity.ok()
        .headers(buildResponseHeaders(metadata))
        .contentLength(metadata.sizeBytes())
        .contentType(parseMediaType(metadata.contentType()))
        .build();
  }

  /**
   * Get structured object metadata as JSON. Object key is passed as a query parameter to avoid
   * Spring Boot 3 PathPatternParser restrictions on catch-all path variables followed by path
   * segments.
   */
  @GetMapping("/buckets/{bucketId}/objects/metadata")
  @Operation(summary = "Get structured object metadata as JSON")
  public ResponseEntity<ObjectMetadataResponse> getMetadata(
      @PathVariable("bucketId") UUID bucketId,
      @RequestParam("objectKey") String objectKey,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {

    ObjectVersionView v = downloadService.getObjectMetadata(bucketId, objectKey, principal);
    return ResponseEntity.ok(
        ObjectMetadataResponse.builder()
            .versionId(v.id())
            .objectId(v.objectId())
            .objectKey(objectKey)
            .contentType(v.contentType())
            .sizeBytes(v.sizeBytes())
            .etag(v.etag())
            .checksumSha256(v.checksumSha256())
            .versionNumber(v.versionNumber())
            .processingStatus(v.processingStatus())
            .virusScanStatus(v.virusScanStatus())
            .thumbnailKey(v.thumbnailKey())
            .createdAt(v.createdAt())
            .build());
  }

  // ============================================================
  // Signed URL
  // ============================================================

  @PostMapping("/download/signed-urls")
  @Operation(summary = "Generate a signed URL for time-limited unauthenticated download")
  public ResponseEntity<SignedUrlResponse> createSignedUrl(
      @Valid @RequestBody CreateSignedUrlRequest request,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {

    SignedUrlResponse response =
        downloadService.generateSignedUrl(
            request.objectVersionId(),
            request.ttlSeconds(),
            request.maxDownloads(),
            request.allowedIp(),
            principal);

    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/download/signed")
  @Operation(summary = "Download via signed URL (no auth required)")
  public ResponseEntity<InputStreamResource> downloadSigned(
      @RequestParam String token, @RequestParam long expires, HttpServletRequest request) {

    String clientIp = extractClientIp(request);
    InputStream stream = downloadService.streamSignedUrl(token, clientIp);

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment")
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(new InputStreamResource(stream));
  }

  // ============================================================
  // Range request handling
  // ============================================================

  private ResponseEntity<InputStreamResource> handleRangeRequest(
      UUID bucketId,
      String objectKey,
      String rangeHeader,
      ObjectVersionView metadata,
      HttpHeaders headers,
      VaultFlowUserPrincipal principal) {

    long totalSize = metadata.sizeBytes();
    long[] range = parseRangeHeader(rangeHeader, totalSize);
    long offset = range[0];
    long end = range[1];
    long length = end - offset + 1;

    InputStream stream =
        downloadService.streamRange(bucketId, objectKey, offset, length, principal);
    headers.add(HttpHeaders.CONTENT_RANGE, "bytes " + offset + "-" + end + "/" + totalSize);

    return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
        .headers(headers)
        .contentLength(length)
        .contentType(parseMediaType(metadata.contentType()))
        .body(new InputStreamResource(stream));
  }

  private long[] parseRangeHeader(String rangeHeader, long totalSize) {
    if (!rangeHeader.startsWith("bytes=")) return new long[] {0, totalSize - 1};
    String range = rangeHeader.substring(6);
    String[] parts = range.split("-");
    if (parts[0].isEmpty()) {
      long suffix = Long.parseLong(parts[1]);
      return new long[] {totalSize - suffix, totalSize - 1};
    }
    long start = Long.parseLong(parts[0]);
    long end = (parts.length > 1 && !parts[1].isEmpty()) ? Long.parseLong(parts[1]) : totalSize - 1;
    return new long[] {start, Math.min(end, totalSize - 1)};
  }

  // ============================================================
  // Helpers
  // ============================================================

  private HttpHeaders buildResponseHeaders(ObjectVersionView metadata) {
    HttpHeaders headers = new HttpHeaders();
    if (metadata.etag() != null) headers.setETag(metadata.etag());
    headers.add(HttpHeaders.ACCEPT_RANGES, "bytes");
    headers.add(HttpHeaders.CACHE_CONTROL, "private, max-age=300");
    if (metadata.checksumSha256() != null)
      headers.add("X-Content-SHA256", metadata.checksumSha256());
    headers.add("X-Object-Version", String.valueOf(metadata.versionNumber()));
    if (metadata.contentDisposition() != null)
      headers.add(HttpHeaders.CONTENT_DISPOSITION, metadata.contentDisposition());
    return headers;
  }

  private MediaType parseMediaType(String contentType) {
    try {
      return contentType != null
          ? MediaType.parseMediaType(contentType)
          : MediaType.APPLICATION_OCTET_STREAM;
    } catch (Exception e) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
  }

  private String extractObjectKey(HttpServletRequest request, String prefix) {
    String uri = request.getRequestURI();
    int idx = uri.indexOf(prefix);
    return idx < 0 ? "" : uri.substring(idx + prefix.length());
  }

  private String extractClientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    return xff != null ? xff.split(",")[0].trim() : request.getRemoteAddr();
  }
}
