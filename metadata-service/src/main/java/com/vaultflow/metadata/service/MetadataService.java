package com.vaultflow.metadata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultflow.common.dto.PageResponse;
import com.vaultflow.common.exception.ResourceNotFoundException;
import com.vaultflow.common.security.VaultFlowUserPrincipal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Metadata service: manages object metadata, tags, version listing, search by key prefix, and
 * lifecycle rule enforcement.
 *
 * <p>Uses JdbcTemplate for all queries — metadata queries are read-heavy, analytically complex, and
 * benefit from direct SQL rather than ORM overhead.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetadataService {

  private final JdbcTemplate jdbc;

  // ============================================================
  // Object metadata
  // ============================================================

  @Transactional(readOnly = true)
  public Map<String, Object> getObjectMetadata(
      UUID bucketId, String objectKey, VaultFlowUserPrincipal principal) {

    var rows =
        jdbc.queryForList(
            """
        SELECT ov.id, ov.object_id, ov.storage_key, ov.size_bytes,
               ov.checksum_sha256, ov.etag, ov.version_number, ov.content_type,
               ov.metadata, ov.tags, ov.is_latest, ov.processing_status,
               ov.thumbnail_key, ov.preview_key, ov.virus_scan_status,
               ov.created_at, o.object_key, o.is_deleted
        FROM object_versions ov
        JOIN objects o ON ov.object_id = o.id
        JOIN buckets b ON o.bucket_id = b.id
        WHERE o.bucket_id = ?::uuid AND o.object_key = ?
          AND b.org_id = ?::uuid AND o.is_deleted = false AND ov.is_latest = true
        LIMIT 1
        """,
            bucketId.toString(),
            objectKey,
            principal.orgId());

    if (rows.isEmpty()) throw new ResourceNotFoundException("Object", objectKey);
    return rows.get(0);
  }

  @Transactional
  public void updateMetadata(
      UUID bucketId,
      String objectKey,
      Map<String, String> metadata,
      Map<String, String> tags,
      VaultFlowUserPrincipal principal) {

    String versionId = getLatestVersionId(bucketId, objectKey, principal.orgId());

    if (metadata != null) {
      jdbc.update(
          "UPDATE object_versions SET metadata = ?::jsonb WHERE id = ?::uuid",
          toJson(metadata),
          versionId);
    }
    if (tags != null) {
      jdbc.update(
          "UPDATE object_versions SET tags = ?::jsonb WHERE id = ?::uuid", toJson(tags), versionId);
    }
    log.info("Metadata updated: objectKey={} versionId={}", objectKey, versionId);
  }

  // ============================================================
  // Object listing and search
  // ============================================================

  @Transactional(readOnly = true)
  public PageResponse<Map<String, Object>> listObjects(
      UUID bucketId,
      String prefix,
      String continuationToken,
      int maxKeys,
      VaultFlowUserPrincipal principal) {

    String likePrefix = prefix != null ? prefix + "%" : "%";

    long total =
        Optional.ofNullable(
                jdbc.queryForObject(
                    """
        SELECT COUNT(*) FROM objects o
        JOIN buckets b ON o.bucket_id = b.id
        WHERE o.bucket_id = ?::uuid AND b.org_id = ?::uuid
          AND o.is_deleted = false AND o.object_key LIKE ?
        """,
                    Long.class,
                    bucketId.toString(),
                    principal.orgId(),
                    likePrefix))
            .orElse(0L);

    var rows =
        jdbc.queryForList(
            """
        SELECT o.id, o.object_key, o.content_type, o.current_version_id,
               o.created_at, o.updated_at,
               ov.size_bytes, ov.etag, ov.checksum_sha256, ov.version_number,
               ov.processing_status, ov.virus_scan_status, ov.thumbnail_key
        FROM objects o
        JOIN buckets b ON o.bucket_id = b.id
        LEFT JOIN object_versions ov ON ov.id = o.current_version_id
        WHERE o.bucket_id = ?::uuid AND b.org_id = ?::uuid
          AND o.is_deleted = false AND o.object_key LIKE ?
        ORDER BY o.object_key ASC
        LIMIT ?
        """,
            bucketId.toString(),
            principal.orgId(),
            likePrefix,
            maxKeys);

    return PageResponse.of(rows, 0, maxKeys, total);
  }

  /**
   * Full-text search on object keys using PostgreSQL pg_trgm trigram index. Production extension:
   * add tsvector column on metadata JSONB for content search.
   */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> searchObjects(String query, UUID orgId, int limit) {
    return jdbc.queryForList(
        """
        SELECT o.id, o.object_key, o.bucket_id, o.content_type,
               ov.size_bytes, ov.created_at,
               similarity(o.object_key, ?) AS score
        FROM objects o
        JOIN buckets b ON o.bucket_id = b.id
        LEFT JOIN object_versions ov ON ov.id = o.current_version_id
        WHERE b.org_id = ?::uuid
          AND o.is_deleted = false
          AND o.object_key % ?
        ORDER BY score DESC
        LIMIT ?
        """,
        query, orgId.toString(), query, limit);
  }

  // ============================================================
  // Version listing
  // ============================================================

  @Transactional(readOnly = true)
  public List<Map<String, Object>> listVersions(
      UUID bucketId, String objectKey, VaultFlowUserPrincipal principal) {

    return jdbc.queryForList(
        """
        SELECT ov.id, ov.version_number, ov.size_bytes, ov.etag,
               ov.checksum_sha256, ov.content_type, ov.is_latest,
               ov.processing_status, ov.virus_scan_status, ov.created_at
        FROM object_versions ov
        JOIN objects o ON ov.object_id = o.id
        JOIN buckets b ON o.bucket_id = b.id
        WHERE o.bucket_id = ?::uuid AND o.object_key = ?
          AND b.org_id = ?::uuid
        ORDER BY ov.version_number DESC
        """,
        bucketId.toString(),
        objectKey,
        principal.orgId());
  }

  // ============================================================
  // Lifecycle rule enforcement (called by scheduler)
  // ============================================================

  /**
   * Apply expiration lifecycle rules. For each bucket with lifecycle rules, find objects older than
   * the configured age threshold and soft-delete them.
   *
   * <p>Called by the LifecycleScheduler every hour.
   */
  @Transactional
  public int applyExpirationRules() {
    // Find objects that match EXPIRE lifecycle rules
    // lifecycle_rules JSON: [{"action": "EXPIRE", "condition": {"ageDays": 90}}]
    int expired =
        jdbc.update(
            """
        UPDATE objects o SET is_deleted = true, deleted_at = NOW(), updated_at = NOW()
        FROM buckets b
        WHERE o.bucket_id = b.id
          AND o.is_deleted = false
          AND b.status = 'ACTIVE'
          AND b.lifecycle_rules::text != '[]'
          AND o.created_at < NOW() - (
            SELECT (rule->>'ageDays')::int * INTERVAL '1 day'
            FROM jsonb_array_elements(b.lifecycle_rules) AS rule
            WHERE rule->>'action' = 'EXPIRE'
            LIMIT 1
          )
        """);

    if (expired > 0) log.info("Lifecycle: expired {} objects", expired);
    return expired;
  }

  // ============================================================
  // Helpers
  // ============================================================

  private String getLatestVersionId(UUID bucketId, String objectKey, String orgId) {
    return Optional.ofNullable(
            jdbc.queryForObject(
                """
        SELECT ov.id::text FROM object_versions ov
        JOIN objects o ON ov.object_id = o.id
        JOIN buckets b ON o.bucket_id = b.id
        WHERE o.bucket_id = ?::uuid AND o.object_key = ?
          AND b.org_id = ?::uuid AND ov.is_latest = true
        """,
                String.class,
                bucketId.toString(),
                objectKey,
                orgId))
        .orElseThrow(() -> new ResourceNotFoundException("Object", objectKey));
  }

  private String toJson(Map<String, String> map) {
    try {
      return new ObjectMapper().writeValueAsString(map);
    } catch (Exception e) {
      return "{}";
    }
  }
}
