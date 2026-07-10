package com.vaultflow.auth.domain.entity;

import com.vaultflow.auth.domain.enums.UserRole;
import com.vaultflow.auth.config.InetType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "org_id", nullable = false)
  private Organization organization;

  @Column(nullable = false)
  private String email;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "full_name", nullable = false)
  private String fullName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Builder.Default
  private UserRole role = UserRole.VIEWER;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  @Builder.Default
  private UserStatus status = UserStatus.ACTIVE;

  @Column(name = "email_verified", nullable = false)
  @Builder.Default
  private Boolean emailVerified = false;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Column(name = "last_login_ip")
  @org.hibernate.annotations.Type(InetType.class)
  private String lastLoginIp;

  @Column(name = "failed_login_count", nullable = false)
  @Builder.Default
  private Integer failedLoginCount = 0;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "created_at", nullable = false, updatable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();

  @Version
  @Column(name = "version")
  private Long version; // Optimistic locking for concurrent profile updates

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }

  // ============================================================
  // Domain behavior
  // ============================================================

  public boolean isActive() {
    return UserStatus.ACTIVE.equals(this.status);
  }

  public boolean isLocked() {
    return this.lockedUntil != null && Instant.now().isBefore(this.lockedUntil);
  }

  /** Called on successful login. Resets failure counter and records access. */
  public void recordSuccessfulLogin(String ipAddress) {
    this.failedLoginCount = 0;
    this.lockedUntil = null;
    this.lastLoginAt = Instant.now();
    this.lastLoginIp = ipAddress;
    this.updatedAt = Instant.now();
  }

  /**
   * Called on failed login. Locks account after 5 consecutive failures using exponential backoff:
   * 5min, 10min, 20min... This prevents brute-force while allowing accidental typos.
   */
  public void recordFailedLogin() {
    this.failedLoginCount++;
    if (this.failedLoginCount >= 5) {
      long lockMinutes = (long) Math.pow(2, Math.min(this.failedLoginCount - 5, 6)) * 5;
      this.lockedUntil = Instant.now().plusSeconds(lockMinutes * 60);
    }
    this.updatedAt = Instant.now();
  }

  public String getOrgId() {
    return organization != null ? organization.getId().toString() : null;
  }

  public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    PENDING_VERIFICATION,
    DELETED
  }
}
