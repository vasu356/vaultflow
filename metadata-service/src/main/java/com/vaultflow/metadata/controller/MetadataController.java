package com.vaultflow.metadata.controller;

import com.vaultflow.common.dto.PageResponse;
import com.vaultflow.common.security.VaultFlowUserPrincipal;
import com.vaultflow.metadata.service.MetadataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Metadata", description = "Object metadata, tags, versioning, search, lifecycle")
public class MetadataController {

  private final MetadataService metadataService;

  @GetMapping("/buckets/{bucketId}/objects")
  @Operation(summary = "List objects in a bucket with optional prefix filter")
  public ResponseEntity<PageResponse<Map<String, Object>>> listObjects(
      @PathVariable("bucketId") UUID bucketId,
      @RequestParam(value = "prefix", required = false, defaultValue = "") String prefix,
      @RequestParam(value = "continuationToken", required = false) String continuationToken,
      @RequestParam(value = "maxKeys", defaultValue = "1000") int maxKeys,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.ok(
        metadataService.listObjects(
            bucketId, prefix, continuationToken, Math.min(maxKeys, 1000), principal));
  }

  @GetMapping("/buckets/{bucketId}/objects/{*objectKey}")
  @Operation(summary = "Get metadata for a single object")
  public ResponseEntity<Map<String, Object>> getObjectMetadata(
      @PathVariable("bucketId") UUID bucketId,
      @PathVariable("objectKey") String objectKey,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.ok(metadataService.getObjectMetadata(bucketId, objectKey, principal));
  }

  @GetMapping("/buckets/{bucketId}/object/versions")
  @Operation(summary = "List all versions of an object")
  public ResponseEntity<List<Map<String, Object>>> listVersions(
      @PathVariable("bucketId") UUID bucketId,
      @RequestParam("objectKey") String objectKey,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.ok(metadataService.listVersions(bucketId, objectKey, principal));
  }

  @PatchMapping("/buckets/{bucketId}/object/metadata")
  @Operation(summary = "Update user-defined metadata and tags on an object")
  public ResponseEntity<Void> updateMetadata(
      @PathVariable("bucketId") UUID bucketId,
      @RequestParam("objectKey") String objectKey,
      @RequestBody Map<String, Map<String, String>> body,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    metadataService.updateMetadata(
        bucketId, objectKey, body.get("metadata"), body.get("tags"), principal);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/search")
  @Operation(summary = "Full-text search across object keys in the organization")
  public ResponseEntity<List<Map<String, Object>>> search(
      @RequestParam("q") String q,
      @RequestParam("limit") int limit,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.ok(
        metadataService.searchObjects(q, UUID.fromString(principal.orgId()), Math.min(limit, 100)));
  }

  private String extractKey(HttpServletRequest req, String prefix, String suffix) {
    String uri = req.getRequestURI();
    int start = uri.indexOf(prefix);
    if (start < 0) return "";
    String key = uri.substring(start + prefix.length());
    if (suffix != null && key.endsWith(suffix)) {
      key = key.substring(0, key.length() - suffix.length());
    }
    return key;
  }
}
