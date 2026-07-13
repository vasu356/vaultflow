package com.vaultflow.admin.controller;

import com.vaultflow.admin.service.AdminService;
import com.vaultflow.common.dto.PageResponse;
import com.vaultflow.common.security.VaultFlowUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Platform operations, analytics, and audit log")
public class AdminController {

  private final AdminService adminService;

  @GetMapping("/overview")
  @PreAuthorize("hasRole('ADMIN') or hasRole('OWNER')")
  @Operation(summary = "Get organization storage and usage overview")
  public ResponseEntity<Map<String, Object>> getOverview(
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.ok(adminService.getOrganizationOverview(principal.orgId()));
  }

  @GetMapping("/analytics/uploads/daily")
  @PreAuthorize("hasRole('ADMIN') or hasRole('OWNER')")
  @Operation(summary = "Daily upload volume for the past N days")
  public ResponseEntity<List<Map<String, Object>>> getDailyUploads(
      @RequestParam("days") int days, @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.ok(adminService.getDailyUploadStats(principal.orgId(), days));
  }

  @GetMapping("/analytics/buckets/top")
  @PreAuthorize("hasRole('ADMIN') or hasRole('OWNER')")
  @Operation(summary = "Top buckets by storage consumption")
  public ResponseEntity<List<Map<String, Object>>> getTopBuckets(
      @RequestParam("limit") int limit, @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.ok(adminService.getTopBucketsByStorage(principal.orgId(), limit));
  }

  @GetMapping("/analytics/content-types")
  @PreAuthorize("hasRole('ADMIN') or hasRole('OWNER')")
  @Operation(summary = "Content type distribution across the organization")
  public ResponseEntity<List<Map<String, Object>>> getContentTypes(
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.ok(adminService.getContentTypeDistribution(principal.orgId()));
  }

  @GetMapping("/audit-log")
  @PreAuthorize("hasRole('ADMIN') or hasRole('OWNER')")
  @Operation(summary = "Paginated audit log with optional filters")
  public ResponseEntity<PageResponse<Map<String, Object>>> getAuditLog(
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String userId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.ok(
        adminService.getAuditLog(principal.orgId(), action, userId, from, to, page, size));
  }

  @PutMapping("/quota")
  @PreAuthorize("hasRole('OWNER')")
  @Operation(summary = "Update organization storage quota (OWNER only)")
  public ResponseEntity<Map<String, Object>> updateQuota(
      @RequestBody Map<String, Long> body,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    Long newQuota = body.get("quotaBytes");
    if (newQuota == null || newQuota <= 0) {
      return ResponseEntity.badRequest().body(Map.of("error", "quotaBytes must be positive"));
    }
    adminService.updateQuota(principal.orgId(), newQuota);
    return ResponseEntity.ok(Map.of("message", "Quota updated", "quotaBytes", newQuota));
  }

  @GetMapping("/health")
  @PreAuthorize("hasRole('ADMIN') or hasRole('OWNER')")
  @Operation(summary = "Platform health and operational metrics")
  public ResponseEntity<Map<String, Object>> getSystemHealth() {
    return ResponseEntity.ok(adminService.getSystemHealth());
  }
}
