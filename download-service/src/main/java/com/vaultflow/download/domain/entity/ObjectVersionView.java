package com.vaultflow.download.domain.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Plain DTO (not a JPA entity) carrying all data needed for a download response.
 *
 * <p>WHY NOT A JPA ENTITY: The download query is a multi-table JOIN across object_versions,
 * objects, and buckets. JPA @Transient fields are not populated by native queries. Using a plain
 * record populated by a JDBC RowMapper gives us full control, avoids the N+1 risk of lazy
 * associations, and is read-only by design.
 *
 * <p>The JpaRepository in the old design is replaced with a JdbcTemplate query in
 * ObjectVersionViewRepository.
 */
public record ObjectVersionView(
    UUID id,
    UUID objectId,
    String storageKey,
    Long sizeBytes,
    String checksumSha256,
    String etag,
    Integer versionNumber,
    String contentType,
    String contentDisposition,
    Boolean isLatest,
    String processingStatus,
    String thumbnailKey,
    String previewKey,
    String virusScanStatus,
    Instant createdAt,
    // JOIN fields
    UUID bucketId,
    String objectKey,
    UUID orgId) {
  public boolean isInfected() {
    return "INFECTED".equals(virusScanStatus);
  }
}
