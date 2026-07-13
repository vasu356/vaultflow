package com.vaultflow.download.domain.entity;

import com.vaultflow.download.persistence.InetType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.Type;

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

  /**
   * PostgreSQL INET column. Hibernate cannot bind a plain String to INET via setString().
   * InetType wraps the value in a PGobject with type="inet" so the JDBC driver sends a
   * properly typed parameter that PostgreSQL accepts.
   */
  @Column(name = "allowed_ip", columnDefinition = "inet")
  @Type(InetType.class)
  private String allowedIp;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
