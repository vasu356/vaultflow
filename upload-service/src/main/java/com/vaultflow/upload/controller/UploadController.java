package com.vaultflow.upload.controller;

import com.vaultflow.common.security.VaultFlowUserPrincipal;
import com.vaultflow.upload.dto.request.UploadRequests.*;
import com.vaultflow.upload.dto.response.UploadResponses.*;
import com.vaultflow.upload.service.BucketService;
import com.vaultflow.upload.service.UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Upload", description = "Object upload, multipart upload, and bucket management")
public class UploadController {

  private final UploadService uploadService;
  private final BucketService bucketService;

  // ============================================================
  // Bucket endpoints
  // ============================================================

  @PostMapping("/buckets")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create a new bucket")
  public ResponseEntity<BucketResponse> createBucket(
      @Valid @RequestBody CreateBucketRequest request,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(bucketService.createBucket(request, principal));
  }

  @GetMapping("/buckets")
  @Operation(summary = "List all buckets in the organization")
  public ResponseEntity<List<BucketResponse>> listBuckets(
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.ok(bucketService.listBuckets(principal));
  }

  @GetMapping("/buckets/{bucketId}")
  @Operation(summary = "Get bucket details")
  public ResponseEntity<BucketResponse> getBucket(
      @PathVariable("bucketId") UUID bucketId,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.ok(bucketService.getBucket(bucketId, principal));
  }

  @DeleteMapping("/buckets/{bucketId}")
  @Operation(summary = "Delete a bucket")
  public ResponseEntity<Void> deleteBucket(
      @PathVariable("bucketId") UUID bucketId,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    bucketService.deleteBucket(bucketId, principal);
    return ResponseEntity.noContent().build();
  }

  // ============================================================
  // Single-part upload
  // ============================================================

  /**
   * Direct streaming upload. Client streams the file body directly — no multipart form encoding.
   * This is the most efficient path for files up to 100 MB.
   *
   * <p>Request: PUT /api/v1/buckets/{bucketId}/objects/{key} Headers: Content-Type, Content-Length,
   * X-Checksum-SHA256 (optional)
   */
  @PutMapping(value = "/buckets/{bucketId}/objects/{*objectKey}")
  @Operation(summary = "Upload a single-part object (streaming, up to 100MB)")
  public ResponseEntity<UploadResponse> uploadObject(
      @PathVariable("bucketId") UUID bucketId,
      @RequestHeader(value = "Content-Type", defaultValue = "application/octet-stream")
          String contentType,
      @RequestHeader(value = "Content-Length", required = false, defaultValue = "0")
          long contentLength,
      @RequestHeader(value = "X-Checksum-SHA256", required = false) String checksum,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal,
      HttpServletRequest request)
      throws Exception {

    // Extract object key from the wildcard path (allows '/' in key names)
    String objectKey = extractObjectKey(request, "/api/v1/buckets/" + bucketId + "/objects/");

    UploadResponse response =
        uploadService.uploadSinglePart(
            bucketId,
            objectKey,
            request.getInputStream(),
            contentLength,
            contentType,
            checksum,
            principal);

    return ResponseEntity.ok().eTag(response.etag()).body(response);
  }

  // ============================================================
  // Multipart upload
  // ============================================================

  @PostMapping("/uploads/initiate")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Initiate a multipart upload session")
  public ResponseEntity<InitiateUploadResponse> initiateUpload(
      @Valid @RequestBody InitiateUploadRequest request,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(uploadService.initiateMultipartUpload(request, principal));
  }

  /**
   * Upload a single part. Can be called in parallel for different part numbers. Part data is
   * streamed directly from the request body (not multipart form).
   */
  @PutMapping("/uploads/{sessionId}/parts/{partNumber}")
  @Operation(summary = "Upload a single part of a multipart upload")
  public ResponseEntity<PartUploadResponse> uploadPart(
      @PathVariable("sessionId") UUID sessionId,
      @PathVariable("partNumber") int partNumber,
      @RequestHeader(value = "Content-Length", defaultValue = "0") long contentLength,
      @RequestHeader(value = "X-Checksum-SHA256", required = false) String checksum,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal,
      HttpServletRequest request)
      throws Exception {

    PartUploadResponse response =
        uploadService.uploadPart(
            sessionId, partNumber, request.getInputStream(), contentLength, checksum, principal);

    return ResponseEntity.ok().eTag(response.etag()).body(response);
  }

  @PostMapping("/uploads/{sessionId}/complete")
  @Operation(summary = "Complete a multipart upload — assembles all parts into the final object")
  public ResponseEntity<UploadResponse> completeUpload(
      @PathVariable("sessionId") UUID sessionId,
      @RequestBody(required = false) CompleteUploadRequest request,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    CompleteUploadRequest req = request != null ? request : new CompleteUploadRequest(null);
    return ResponseEntity.ok(uploadService.completeMultipartUpload(sessionId, req, principal));
  }

  @DeleteMapping("/uploads/{sessionId}")
  @Operation(summary = "Abort a multipart upload and clean up parts")
  public ResponseEntity<Void> abortUpload(
      @PathVariable("sessionId") UUID sessionId,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    uploadService.abortUpload(sessionId, principal);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/uploads/{sessionId}/status")
  @Operation(summary = "Get multipart upload status — used for resumable upload recovery")
  public ResponseEntity<UploadStatusResponse> getUploadStatus(
      @PathVariable("sessionId") UUID sessionId,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.ok(uploadService.getUploadStatus(sessionId, principal));
  }

  // ============================================================
  // Object management
  // ============================================================

  @DeleteMapping("/buckets/{bucketId}/objects/{*objectKey}")
  @Operation(summary = "Soft-delete an object (restorable)")
  public ResponseEntity<Void> deleteObject(
      @PathVariable("bucketId") UUID bucketId,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal,
      HttpServletRequest request) {
    String objectKey = extractObjectKey(request, "/api/v1/buckets/" + bucketId + "/objects/");
    uploadService.softDeleteObject(bucketId, objectKey, principal);
    return ResponseEntity.noContent().build();
  }

  /**
   * Restore a soft-deleted object. Object key is passed as a query parameter to avoid Spring Boot 3
   * PathPatternParser restrictions on catch-all path variables followed by path segments.
   */
  @PostMapping("/buckets/{bucketId}/objects/restore")
  @Operation(summary = "Restore a soft-deleted object")
  public ResponseEntity<Void> restoreObject(
      @PathVariable("bucketId") UUID bucketId,
      @RequestParam("key") String objectKey,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    uploadService.restoreObject(bucketId, objectKey, principal);
    return ResponseEntity.ok().build();
  }

  private String extractObjectKey(HttpServletRequest request, String prefix) {
    String uri = request.getRequestURI();
    int idx = uri.indexOf(prefix);
    if (idx < 0) return "";
    return uri.substring(idx + prefix.length());
  }
}
