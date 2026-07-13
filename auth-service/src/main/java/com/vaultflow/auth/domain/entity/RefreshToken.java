package com.vaultflow.auth.domain.entity;

import com.vaultflow.auth.config.InetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "token_hash", nullable = false, unique = true)
  private String tokenHash; // SHA-256 of raw token — stored hash prevents DB exposure

  @Column(name = "family_id", nullable = false)
  private UUID familyId;

  @Column(name = "device_info")
  private String deviceInfo;

  @Column(name = "ip_address")
  @org.hibernate.annotations.Type(InetType.class)
  private String ipAddress;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(nullable = false)
  @Builder.Default
  private Boolean revoked = false;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revoke_reason")
  private String revokeReason;

  @Column(name = "created_at", nullable = false, updatable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  public boolean isExpired() {
    return Instant.now().isAfter(this.expiresAt);
  }

  public boolean isValid() {
    return !revoked && !isExpired();
  }

  public void revoke(String reason) {
    this.revoked = true;
    this.revokedAt = Instant.now();
    this.revokeReason = reason;
  }
}
