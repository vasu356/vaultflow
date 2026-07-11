package com.vaultflow.download.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "signed_urls")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignedUrlRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "object_version_id", nullable = false)
  private UUID objectVersionId;

  @Column(nullable = false, unique = true)
  private String token;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "max_downloads")
  private Integer maxDownloads;

  @Column(name = "download_count", nullable = false)
  @Builder.Default
  private Integer downloadCount = 0;

  @Column(name = "allowed_ip", columnDefinition = "inet")
  private String allowedIp;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
