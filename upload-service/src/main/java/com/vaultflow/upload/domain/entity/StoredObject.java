package com.vaultflow.upload.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "objects")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoredObject {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "bucket_id", nullable = false)
  private UUID bucketId;

  @Column(name = "object_key", nullable = false)
  private String objectKey;

  @Column(name = "current_version_id")
  private UUID currentVersionId;

  @Column(name = "content_type")
  private String contentType;

  @Column(name = "is_deleted", nullable = false)
  @Builder.Default
  private Boolean isDeleted = false;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();

  @Version private Long version;

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }

  public void softDelete() {
    this.isDeleted = true;
    this.deletedAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void restore() {
    this.isDeleted = false;
    this.deletedAt = null;
    this.updatedAt = Instant.now();
  }
}
