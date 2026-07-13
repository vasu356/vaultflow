package com.vaultflow.upload.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "upload_parts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadPart {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "session_id", nullable = false)
  private UUID sessionId;

  @Column(name = "part_number", nullable = false)
  private Integer partNumber;

  @Column(name = "storage_key", nullable = false)
  private String storageKey;

  @Column(name = "size_bytes", nullable = false)
  private Long sizeBytes;

  @Column(name = "checksum_md5", nullable = false)
  private String checksumMd5;

  @Column(name = "checksum_sha256", nullable = false)
  private String checksumSha256;

  @Column(name = "received_at", nullable = false, updatable = false)
  @Builder.Default
  private Instant receivedAt = Instant.now();
}
