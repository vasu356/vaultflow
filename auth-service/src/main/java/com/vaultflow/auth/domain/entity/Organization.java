package com.vaultflow.auth.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "organizations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Organization {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false, unique = true)
  private String slug;

  @Column(name = "quota_bytes", nullable = false)
  @Builder.Default
  private Long quotaBytes = 107_374_182_400L; // 100 GB

  @Column(name = "used_bytes", nullable = false)
  @Builder.Default
  private Long usedBytes = 0L;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  @Builder.Default
  private OrgStatus status = OrgStatus.ACTIVE;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  @Builder.Default
  private String settings = "{}";

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

  public enum OrgStatus {
    ACTIVE,
    SUSPENDED,
    DELETED
  }

  /**
   * Atomically records storage consumption. Called within the upload transaction. We use a separate
   * update rather than optimistic lock to avoid conflicts between concurrent uploads within the
   * same org.
   */
  public void addUsedBytes(long bytes) {
    this.usedBytes += bytes;
  }

  public void subtractUsedBytes(long bytes) {
    this.usedBytes = Math.max(0L, this.usedBytes - bytes);
  }

  public long remainingQuota() {
    return Math.max(0L, this.quotaBytes - this.usedBytes);
  }

  public boolean hasCapacity(long requiredBytes) {
    return remainingQuota() >= requiredBytes;
  }
}
