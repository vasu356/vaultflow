package com.vaultflow.upload.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vaultflow.common.exception.InvalidUploadException;
import com.vaultflow.common.exception.QuotaExceededException;
import com.vaultflow.common.security.VaultFlowUserPrincipal;
import com.vaultflow.upload.domain.entity.*;
import com.vaultflow.upload.domain.repository.*;
import com.vaultflow.upload.dto.request.UploadRequests.InitiateUploadRequest;
import com.vaultflow.upload.dto.response.UploadResponses.InitiateUploadResponse;
import com.vaultflow.upload.kafka.UploadEventPublisher;
import com.vaultflow.upload.storage.ObjectStoragePort;
import com.vaultflow.upload.util.ContentTypeDetector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("UploadService")
class UploadServiceTest {

  @Mock BucketRepository bucketRepository;
  @Mock StoredObjectRepository objectRepository;
  @Mock ObjectVersionRepository versionRepository;
  @Mock UploadSessionRepository sessionRepository;
  @Mock UploadPartRepository partRepository;
  @Mock ObjectStoragePort storage;
  @Mock UploadEventPublisher eventPublisher;
  @Mock StringRedisTemplate redisTemplate;
  @Mock ValueOperations<String, String> valueOps;
  @Mock QuotaService quotaService;
  @Mock ContentTypeDetector contentTypeDetector;

  UploadService uploadService;

  VaultFlowUserPrincipal principal;
  UUID orgId = UUID.randomUUID();
  UUID userId = UUID.randomUUID();
  UUID bucketId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    uploadService = new UploadService(
        bucketRepository, objectRepository, versionRepository,
        sessionRepository, partRepository, storage, eventPublisher,
        redisTemplate, contentTypeDetector, new SimpleMeterRegistry(), quotaService);

    principal = new VaultFlowUserPrincipal(
        userId.toString(), "user@test.com", orgId.toString(),
        "EDITOR", List.of("read", "write", "delete"), null);
  }

  @Nested
  @DisplayName("initiateMultipartUpload")
  class InitiateMultipart {

    @Test
    @DisplayName("creates upload session with correct fields")
    void createsSession() {
      Bucket bucket = Bucket.builder().id(bucketId).orgId(orgId).name("test-bucket").build();
      when(bucketRepository.findByIdAndOrgId(bucketId, orgId)).thenReturn(Optional.of(bucket));
      when(sessionRepository.save(any())).thenAnswer(inv -> {
        UploadSession s = inv.getArgument(0);
        s = UploadSession.builder()
            .id(UUID.randomUUID()).bucketId(bucketId).objectKey(s.getObjectKey())
            .orgId(orgId).initiatedBy(userId).contentType(s.getContentType())
            .status(com.vaultflow.upload.domain.enums.UploadStatus.INITIATED).build();
        return s;
      });

      InitiateUploadRequest request = new InitiateUploadRequest(
          bucketId, "documents/report.pdf", "application/pdf", 50_000_000L, 5);

      InitiateUploadResponse response = uploadService.initiateMultipartUpload(request, principal);

      assertThat(response.sessionId()).isNotNull();
      assertThat(response.objectKey()).isEqualTo("documents/report.pdf");
      assertThat(response.bucketId()).isEqualTo(bucketId);

      ArgumentCaptor<UploadSession> captor = ArgumentCaptor.forClass(UploadSession.class);
      verify(sessionRepository).save(captor.capture());
      assertThat(captor.getValue().getContentType()).isEqualTo("application/pdf");
      assertThat(captor.getValue().getTotalParts()).isEqualTo(5);
    }

    @Test
    @DisplayName("throws ResourceNotFoundException when bucket not found in org")
    void bucketNotFound() {
      when(bucketRepository.findByIdAndOrgId(bucketId, orgId)).thenReturn(Optional.empty());

      InitiateUploadRequest request = new InitiateUploadRequest(
          bucketId, "test.txt", "text/plain", null, null);

      assertThatThrownBy(() -> uploadService.initiateMultipartUpload(request, principal))
          .isInstanceOf(com.vaultflow.common.exception.ResourceNotFoundException.class);

      verify(sessionRepository, never()).save(any());
    }
  }

  @Nested
  @DisplayName("uploadPart")
  class UploadPartTests {

    @Test
    @DisplayName("rejects part number 0")
    void invalidPartNumberZero() {
      assertThatThrownBy(() -> uploadService.uploadPart(
          UUID.randomUUID(), 0, new ByteArrayInputStream(new byte[0]), 0, null, principal))
          .isInstanceOf(InvalidUploadException.class)
          .hasMessageContaining("Part number must be between 1 and 10000");
    }

    @Test
    @DisplayName("rejects part number above 10000")
    void invalidPartNumberTooHigh() {
      assertThatThrownBy(() -> uploadService.uploadPart(
          UUID.randomUUID(), 10001, new ByteArrayInputStream(new byte[0]), 0, null, principal))
          .isInstanceOf(InvalidUploadException.class);
    }

    @Test
    @DisplayName("stores part and records in repository")
    void storesPartSuccessfully() {
      UUID sessionId = UUID.randomUUID();
      byte[] partData = new byte[10 * 1024 * 1024]; // 10 MB
      new Random().nextBytes(partData);

      UploadSession session = UploadSession.builder()
          .id(sessionId).bucketId(bucketId).objectKey("test.bin")
          .orgId(orgId).initiatedBy(userId).contentType("application/octet-stream")
          .status(com.vaultflow.upload.domain.enums.UploadStatus.INITIATED).build();

      when(sessionRepository.findByIdAndOrgId(sessionId, orgId)).thenReturn(Optional.of(session));
      when(storage.storePart(any(), eq(1), any(), anyLong())).thenReturn("part_00001");
      when(partRepository.findBySessionIdAndPartNumber(sessionId, 1)).thenReturn(Optional.empty());
      when(partRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
      when(sessionRepository.save(any())).thenReturn(session);
      when(partRepository.countBySessionId(sessionId)).thenReturn(1L);

      var response = uploadService.uploadPart(
          sessionId, 1, new ByteArrayInputStream(partData), partData.length, null, principal);

      assertThat(response.partNumber()).isEqualTo(1);
      assertThat(response.sizeBytes()).isEqualTo(partData.length);
      assertThat(response.checksumSha256()).isNotBlank();
      verify(storage).storePart(eq(sessionId.toString()), eq(1), any(), eq((long) partData.length));
    }
  }

  @Nested
  @DisplayName("abortUpload")
  class AbortUpload {

    @Test
    @DisplayName("cleans up all parts and marks session aborted")
    void abortsCleanly() {
      UUID sessionId = UUID.randomUUID();
      UploadSession session = UploadSession.builder()
          .id(sessionId).bucketId(bucketId).objectKey("test.bin")
          .orgId(orgId).initiatedBy(userId).contentType("application/octet-stream")
          .status(com.vaultflow.upload.domain.enums.UploadStatus.UPLOADING).build();

      UploadPart part1 = UploadPart.builder().id(UUID.randomUUID()).sessionId(sessionId)
          .partNumber(1).storageKey("part_00001").sizeBytes(5000L)
          .checksumMd5("md5").checksumSha256("sha").build();
      UploadPart part2 = UploadPart.builder().id(UUID.randomUUID()).sessionId(sessionId)
          .partNumber(2).storageKey("part_00002").sizeBytes(5000L)
          .checksumMd5("md5").checksumSha256("sha").build();

      when(sessionRepository.findByIdAndOrgId(sessionId, orgId)).thenReturn(Optional.of(session));
      when(partRepository.findBySessionIdOrderByPartNumberAsc(sessionId))
          .thenReturn(List.of(part1, part2));
      when(sessionRepository.save(any())).thenReturn(session);

      uploadService.abortUpload(sessionId, principal);

      verify(storage).deletePart(sessionId.toString(), 1, "part_00001");
      verify(storage).deletePart(sessionId.toString(), 2, "part_00002");
      verify(partRepository).deleteBySessionId(sessionId);

      ArgumentCaptor<UploadSession> captor = ArgumentCaptor.forClass(UploadSession.class);
      verify(sessionRepository).save(captor.capture());
      assertThat(captor.getValue().getStatus())
          .isEqualTo(com.vaultflow.upload.domain.enums.UploadStatus.ABORTED);
    }
  }
}
