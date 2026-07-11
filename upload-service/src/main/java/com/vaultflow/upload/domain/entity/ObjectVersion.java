package com.vaultflow.upload.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "object_versions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectVersion {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "object_id", nullable = false)
  private UUID objectId;

  @Column(name = "storage_key", nullable = false)
  private String storageKey;

  @Column(name = "size_bytes", nullable = false)
  private Long sizeBytes;

  @Column(name = "checksum_sha256", nullable = false)
  private String checksumSha256;

  @Column(nullable = false)
  private String etag;

  @Column(name = "version_number", nullable = false)
  private Integer versionNumber;

  @Column(name = "storage_class", nullable = false)
  @Builder.Default
  private String storageClass = "STANDARD";

  @Column(name = "content_type", nullable = false)
  private String contentType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  @Builder.Default
  private String metadata = "{}";

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  @Builder.Default
  private String tags = "{}";

  @Column(name = "is_latest", nullable = false)
  @Builder.Default
  private Boolean isLatest = true;

  @Column(name = "is_delete_marker", nullable = false)
  @Builder.Default
  private Boolean isDeleteMarker = false;

  @Column(name = "processing_status", nullable = false)
  @Builder.Default
  private String processingStatus = "PENDING";

  @Column(name = "thumbnail_key")
  private String thumbnailKey;

  @Column(name = "preview_key")
  private String previewKey;

  @Column(name = "virus_scan_status", nullable = false)
  @Builder.Default
  private String virusScanStatus = "PENDING";

  @Column(name = "virus_scan_at")
  private Instant virusScanAt;

  @Column(name = "ref_count", nullable = false)
  @Builder.Default
  private Integer refCount = 1;

  @Column(name = "uploaded_by")
  private UUID uploadedBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  public void markNotLatest() {
    this.isLatest = false;
  }
}
