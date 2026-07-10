package com.vaultflow.upload.domain.entity;

import com.vaultflow.upload.domain.enums.UploadStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "upload_sessions")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class UploadSession {

  @Id @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "bucket_id", nullable = false)
  private UUID bucketId;

  @Column(name = "object_key", nullable = false)
  private String objectKey;

  @Column(name = "org_id", nullable = false)
  private UUID orgId;

  @Column(name = "initiated_by", nullable = false)
  private UUID initiatedBy;

  @Column(name = "content_type", nullable = false)
  private String contentType;

  @Column(name = "expected_size")
  private Long expectedSize;

  @Column(name = "total_parts")
  private Integer totalParts;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  @Builder.Default
  private String metadata = "{}";

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  @Builder.Default
  private String tags = "{}";

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Builder.Default
  private UploadStatus status = UploadStatus.INITIATED;

  @Column(name = "object_id")
  private UUID objectId;

  @Column(name = "version_id")
  private UUID versionId;

  @Column(name = "expires_at", nullable = false)
  @Builder.Default
  private Instant expiresAt = Instant.now().plusSeconds(86400);

  @Column(name = "created_at", nullable = false, updatable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();

  @PreUpdate
  void onUpdate() { this.updatedAt = Instant.now(); }

  public boolean isExpired() {
    return Instant.now().isAfter(this.expiresAt);
  }

  public boolean isActive() {
    return status == UploadStatus.INITIATED || status == UploadStatus.UPLOADING;
  }
}
