package com.vaultflow.upload.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "buckets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Bucket {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "org_id", nullable = false)
  private UUID orgId;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  @Builder.Default
  private String region = "ap-south-1";

  @Column(name = "versioning_enabled", nullable = false)
  @Builder.Default
  private Boolean versioningEnabled = false;

  @Column(name = "public_access_enabled", nullable = false)
  @Builder.Default
  private Boolean publicAccessEnabled = false;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "lifecycle_rules", columnDefinition = "jsonb")
  @Builder.Default
  private String lifecycleRules = "[]";

  @Column(name = "retention_days")
  private Integer retentionDays;

  @Column(nullable = false)
  @Builder.Default
  private String status = "ACTIVE";

  @Column(name = "created_at", nullable = false, updatable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }
}
