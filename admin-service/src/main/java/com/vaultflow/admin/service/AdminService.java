package com.vaultflow.admin.service;

import com.vaultflow.common.dto.PageResponse;
import com.vaultflow.common.exception.ResourceNotFoundException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Admin service providing platform-wide operational capabilities.
 *
 * <p>Uses JdbcTemplate for analytics queries — these are complex aggregations that JPA/JPQL would
 * handle poorly. Native SQL gives full access to PostgreSQL window functions, CTEs, and
 * partition-aware queries on audit_logs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {

  private final JdbcTemplate jdbc;

  // ============================================================
  // Organization overview
  // ============================================================

  public Map<String, Object> getOrganizationOverview(String orgId) {
    Map<String, Object> overview = new LinkedHashMap<>();

    // Storage usage
    Map<String, Object> storage =
        jdbc.queryForMap(
            "SELECT quota_bytes, used_bytes, "
                + "ROUND(used_bytes::numeric / NULLIF(quota_bytes, 0) * 100, 2) AS used_pct "
                + "FROM organizations WHERE id = ?::uuid",
            orgId);
    overview.put("storage", storage);

    // Object counts
    Long totalObjects =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM objects o "
                + "JOIN buckets b ON o.bucket_id = b.id "
                + "WHERE b.org_id = ?::uuid AND o.is_deleted = false",
            Long.class,
            orgId);
    overview.put("totalObjects", totalObjects);

    // Bucket count
    Long totalBuckets =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM buckets WHERE org_id = ?::uuid AND status = 'ACTIVE'",
            Long.class,
            orgId);
    overview.put("totalBuckets", totalBuckets);

    // User count
    Long totalUsers =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE org_id = ?::uuid AND status = 'ACTIVE'",
            Long.class,
            orgId);
    overview.put("totalUsers", totalUsers);

    // Processing pipeline status
    Map<String, Object> processingStats =
        jdbc.queryForMap(
            "SELECT "
                + "  COUNT(*) FILTER (WHERE processing_status = 'PENDING') AS pending, "
                + "  COUNT(*) FILTER (WHERE processing_status = 'PROCESSING') AS processing, "
                + "  COUNT(*) FILTER (WHERE processing_status = 'COMPLETED') AS completed, "
                + "  COUNT(*) FILTER (WHERE processing_status = 'FAILED') AS failed "
                + "FROM object_versions ov "
                + "JOIN objects o ON ov.object_id = o.id "
                + "JOIN buckets b ON o.bucket_id = b.id "
                + "WHERE b.org_id = ?::uuid",
            orgId);
    overview.put("processingPipeline", processingStats);

    return overview;
  }

  // ============================================================
  // Usage Analytics
  // ============================================================

  /**
   * Daily upload volume over the last N days. Useful for capacity planning and billing dashboards.
   */
  public List<Map<String, Object>> getDailyUploadStats(String orgId, int days) {
    return jdbc.queryForList(
        "SELECT "
            + "  DATE_TRUNC('day', ov.created_at) AS day, "
            + "  COUNT(*) AS upload_count, "
            + "  SUM(ov.size_bytes) AS total_bytes, "
            + "  AVG(ov.size_bytes) AS avg_bytes "
            + "FROM object_versions ov "
            + "JOIN objects o ON ov.object_id = o.id "
            + "JOIN buckets b ON o.bucket_id = b.id "
            + "WHERE b.org_id = ?::uuid "
            + "  AND ov.created_at >= NOW() - INTERVAL '1 day' * ? "
            + "  AND ov.is_delete_marker = false "
            + "GROUP BY DATE_TRUNC('day', ov.created_at) "
            + "ORDER BY day DESC",
        orgId,
        days);
  }

  /** Top N buckets by storage usage. */
  public List<Map<String, Object>> getTopBucketsByStorage(String orgId, int limit) {
    return jdbc.queryForList(
        "SELECT "
            + "  b.name AS bucket_name, "
            + "  b.id AS bucket_id, "
            + "  COUNT(DISTINCT o.id) AS object_count, "
            + "  SUM(ov.size_bytes) AS total_bytes, "
            + "  MAX(ov.created_at) AS last_upload_at "
            + "FROM buckets b "
            + "LEFT JOIN objects o ON o.bucket_id = b.id AND o.is_deleted = false "
            + "LEFT JOIN object_versions ov ON ov.object_id = o.id AND ov.is_latest = true "
            + "WHERE b.org_id = ?::uuid AND b.status = 'ACTIVE' "
            + "GROUP BY b.id, b.name "
            + "ORDER BY total_bytes DESC NULLS LAST "
            + "LIMIT ?",
        orgId,
        limit);
  }

  /** Content type distribution across the organization. Useful for storage tiering decisions. */
  public List<Map<String, Object>> getContentTypeDistribution(String orgId) {
    return jdbc.queryForList(
        "SELECT "
            + "  COALESCE(SPLIT_PART(ov.content_type, '/', 1), 'unknown') AS media_type, "
            + "  ov.content_type, "
            + "  COUNT(*) AS count, "
            + "  SUM(ov.size_bytes) AS total_bytes "
            + "FROM object_versions ov "
            + "JOIN objects o ON ov.object_id = o.id "
            + "JOIN buckets b ON o.bucket_id = b.id "
            + "WHERE b.org_id = ?::uuid AND ov.is_latest = true "
            + "GROUP BY SPLIT_PART(ov.content_type, '/', 1), ov.content_type "
            + "ORDER BY count DESC "
            + "LIMIT 50",
        orgId);
  }

  // ============================================================
  // Audit Log
  // ============================================================

  public PageResponse<Map<String, Object>> getAuditLog(
      String orgId, String action, String userId, Instant from, Instant to, int page, int size) {

    String countSql =
        "SELECT COUNT(*) FROM audit_logs "
            + "WHERE org_id = ?::uuid "
            + (action != null ? "AND action = ? " : "")
            + (userId != null ? "AND user_id = ?::uuid " : "")
            + "AND occurred_at BETWEEN ? AND ?";

    String dataSql =
        "SELECT id, user_id, action, resource_type, resource_id, "
            + "ip_address, correlation_id, outcome, occurred_at "
            + "FROM audit_logs "
            + "WHERE org_id = ?::uuid "
            + (action != null ? "AND action = ? " : "")
            + (userId != null ? "AND user_id = ?::uuid " : "")
            + "AND occurred_at BETWEEN ? AND ? "
            + "ORDER BY occurred_at DESC "
            + "LIMIT ? OFFSET ?";

    List<Object> params = new ArrayList<>();
    params.add(orgId);
    if (action != null) params.add(action);
    if (userId != null) params.add(userId);
    Instant effectiveFrom = from != null ? from : Instant.now().minus(7, ChronoUnit.DAYS);
    Instant effectiveTo = to != null ? to : Instant.now();
    params.add(java.sql.Timestamp.from(effectiveFrom));
    params.add(java.sql.Timestamp.from(effectiveTo));

    Long total = jdbc.queryForObject(countSql, Long.class, params.toArray());

    List<Object> dataParams = new ArrayList<>(params);
    dataParams.add(size);
    dataParams.add(page * size);

    List<Map<String, Object>> rows = jdbc.queryForList(dataSql, dataParams.toArray());

    return PageResponse.of(rows, page, size, total != null ? total : 0L);
  }

  // ============================================================
  // Quota Management
  // ============================================================

  public void updateQuota(String orgId, long newQuotaBytes) {
    int updated =
        jdbc.update(
            "UPDATE organizations SET quota_bytes = ?, updated_at = NOW() WHERE id = ?::uuid",
            newQuotaBytes,
            orgId);
    if (updated == 0) {
      throw new ResourceNotFoundException("Organization", orgId);
    }
    log.info("Quota updated: orgId={} newQuota={}", orgId, newQuotaBytes);
  }

  // ============================================================
  // System Health
  // ============================================================

  public Map<String, Object> getSystemHealth() {
    Map<String, Object> health = new LinkedHashMap<>();

    // DB connectivity
    try {
      jdbc.queryForObject("SELECT 1", Integer.class);
      health.put("database", Map.of("status", "UP"));
    } catch (Exception e) {
      health.put("database", Map.of("status", "DOWN", "error", e.getMessage()));
    }

    // Pending processing jobs
    Long pendingJobs =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM object_versions WHERE processing_status = 'PENDING'", Long.class);
    health.put("pendingProcessingJobs", pendingJobs);

    // Infected files (critical — needs immediate attention)
    Long infectedFiles =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM object_versions WHERE virus_scan_status = 'INFECTED'",
            Long.class);
    health.put("infectedFiles", infectedFiles);

    // Expired upload sessions (should be cleaned up)
    Long expiredSessions =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM upload_sessions WHERE status IN ('INITIATED','UPLOADING') "
                + "AND expires_at < NOW()",
            Long.class);
    health.put("expiredUploadSessions", expiredSessions);

    health.put("timestamp", Instant.now());
    return health;
  }
}
