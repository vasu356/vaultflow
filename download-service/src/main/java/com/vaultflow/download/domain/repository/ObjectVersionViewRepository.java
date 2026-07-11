package com.vaultflow.download.domain.repository;

import com.vaultflow.download.domain.entity.ObjectVersionView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC-based repository for ObjectVersionView.
 *
 * <p>Uses JdbcTemplate instead of JpaRepository because: 1. The query joins three tables — JPA
 * would require multiple entity relationships and separate queries or complex JPQL 2. Result
 * includes columns from different tables that don't map to one entity 3. This is a read-only query
 * — no ORM overhead needed 4. Full control over the exact SQL for performance tuning
 */
@Repository
@RequiredArgsConstructor
public class ObjectVersionViewRepository {

  private final JdbcTemplate jdbc;

  private static final String FIND_CURRENT_SQL =
      """
      SELECT
        ov.id, ov.object_id, ov.storage_key, ov.size_bytes,
        ov.checksum_sha256, ov.etag, ov.version_number,
        ov.content_type, ov.content_disposition, ov.is_latest,
        ov.processing_status, ov.thumbnail_key, ov.preview_key,
        ov.virus_scan_status, ov.created_at,
        o.bucket_id, o.object_key,
        b.org_id
      FROM object_versions ov
      JOIN objects o ON ov.object_id = o.id
      JOIN buckets b ON o.bucket_id = b.id
      WHERE o.bucket_id = ?::uuid
        AND o.object_key = ?
        AND b.org_id = ?::uuid
        AND o.is_deleted = false
        AND ov.is_latest = true
        AND ov.is_delete_marker = false
      LIMIT 1
      """;

  private static final String FIND_BY_ID_SQL =
      """
      SELECT
        ov.id, ov.object_id, ov.storage_key, ov.size_bytes,
        ov.checksum_sha256, ov.etag, ov.version_number,
        ov.content_type, ov.content_disposition, ov.is_latest,
        ov.processing_status, ov.thumbnail_key, ov.preview_key,
        ov.virus_scan_status, ov.created_at,
        o.bucket_id, o.object_key,
        b.org_id
      FROM object_versions ov
      JOIN objects o ON ov.object_id = o.id
      JOIN buckets b ON o.bucket_id = b.id
      WHERE ov.id = ?::uuid
      LIMIT 1
      """;

  public Optional<ObjectVersionView> findCurrentVersionByBucketAndKey(
      UUID bucketId, String objectKey, UUID orgId) {
    var results =
        jdbc.query(
            FIND_CURRENT_SQL, this::mapRow, bucketId.toString(), objectKey, orgId.toString());
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  public Optional<ObjectVersionView> findById(UUID id) {
    var results = jdbc.query(FIND_BY_ID_SQL, this::mapRow, id.toString());
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  private ObjectVersionView mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new ObjectVersionView(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("object_id")),
        rs.getString("storage_key"),
        rs.getLong("size_bytes"),
        rs.getString("checksum_sha256"),
        rs.getString("etag"),
        rs.getInt("version_number"),
        rs.getString("content_type"),
        rs.getString("content_disposition"),
        rs.getBoolean("is_latest"),
        rs.getString("processing_status"),
        rs.getString("thumbnail_key"),
        rs.getString("preview_key"),
        rs.getString("virus_scan_status"),
        rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
        UUID.fromString(rs.getString("bucket_id")),
        rs.getString("object_key"),
        UUID.fromString(rs.getString("org_id")));
  }
}
