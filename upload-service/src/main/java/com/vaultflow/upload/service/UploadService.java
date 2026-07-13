package com.vaultflow.upload.service;

import com.vaultflow.common.event.AuditEvent;
import com.vaultflow.common.event.FileUploadedEvent;
import com.vaultflow.common.exception.InvalidUploadException;
import com.vaultflow.common.exception.ResourceNotFoundException;
import com.vaultflow.common.exception.VaultFlowException;
import com.vaultflow.common.security.VaultFlowUserPrincipal;
import com.vaultflow.common.util.ChecksumUtil;
import com.vaultflow.upload.domain.entity.*;
import com.vaultflow.upload.domain.enums.UploadStatus;
import com.vaultflow.upload.domain.repository.*;
import com.vaultflow.upload.dto.request.UploadRequests.*;
import com.vaultflow.upload.dto.response.UploadResponses.*;
import com.vaultflow.upload.kafka.UploadEventPublisher;
import com.vaultflow.upload.storage.ObjectStoragePort;
import com.vaultflow.upload.util.ContentTypeDetector;
import com.vaultflow.upload.util.EtagUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UploadService {

  private final BucketRepository bucketRepository;
  private final StoredObjectRepository objectRepository;
  private final ObjectVersionRepository versionRepository;
  private final UploadSessionRepository sessionRepository;
  private final UploadPartRepository partRepository;
  private final ObjectStoragePort storage;
  private final UploadEventPublisher eventPublisher;
  private final StringRedisTemplate redisTemplate;
  private final ContentTypeDetector contentTypeDetector;
  private final MeterRegistry meterRegistry;
  private final QuotaService quotaService;

  private static final long MAX_SINGLE_PART_SIZE = 100L * 1024 * 1024;
  private static final String LOCK_PREFIX = "lock:upload:session:";

  @Transactional
  public UploadResponse uploadSinglePart(
      UUID bucketId,
      String objectKey,
      InputStream data,
      long contentLength,
      String contentType,
      String expectedChecksum,
      VaultFlowUserPrincipal principal) {

    Timer.Sample timer = Timer.start(meterRegistry);
    validateBucketAccess(bucketId, principal.orgId());

    if (contentLength > MAX_SINGLE_PART_SIZE) {
      throw new InvalidUploadException("File exceeds single-part limit. Use multipart upload.");
    }

    quotaService.assertQuota(UUID.fromString(principal.orgId()), contentLength);

    byte[] fileBytes;
    try {
      fileBytes = IOUtils.toByteArray(data);
    } catch (IOException e) {
      throw new com.vaultflow.common.exception.StorageException("Failed to read upload stream", e);
    }

    String resolvedContentType =
        contentTypeDetector.detect(new ByteArrayInputStream(fileBytes), contentType, objectKey);
    String checksum = ChecksumUtil.sha256Hex(fileBytes);

    if (expectedChecksum != null && !expectedChecksum.isBlank()) {
      ChecksumUtil.verify(expectedChecksum.toLowerCase(), checksum);
    }

    String storageKey = ChecksumUtil.toStoragePath(checksum);
    boolean isDuplicate = storage.exists(storageKey);
    if (!isDuplicate) {
      storage.store(
          storageKey, new ByteArrayInputStream(fileBytes), fileBytes.length, resolvedContentType);
    }

    StoredObject object =
        objectRepository
            .findByBucketIdAndObjectKeyAndIsDeletedFalse(bucketId, objectKey)
            .orElseGet(
                () ->
                    objectRepository.save(
                        StoredObject.builder()
                            .bucketId(bucketId)
                            .objectKey(objectKey)
                            .contentType(resolvedContentType)
                            .build()));

    if (object.getCurrentVersionId() != null) {
      versionRepository.markAllNotLatest(object.getId());
    }

    int nextVersion =
        versionRepository.findMaxVersionNumber(object.getId()).map(n -> n + 1).orElse(1);
    String etag = '"' + checksum.substring(0, 32) + '"';

    ObjectVersion version =
        versionRepository.save(
            ObjectVersion.builder()
                .objectId(object.getId())
                .storageKey(storageKey)
                .sizeBytes((long) fileBytes.length)
                .checksumSha256(checksum)
                .etag(etag)
                .versionNumber(nextVersion)
                .contentType(resolvedContentType)
                .isLatest(true)
                .uploadedBy(UUID.fromString(principal.userId()))
                .refCount(
                    isDuplicate ? (int) versionRepository.countByStorageKey(storageKey) + 1 : 1)
                .build());

    object.setCurrentVersionId(version.getId());
    object.setContentType(resolvedContentType);
    objectRepository.save(object);

    if (!isDuplicate) {
      quotaService.consumeQuota(UUID.fromString(principal.orgId()), fileBytes.length);
    }

    eventPublisher.publishFileUploaded(
        FileUploadedEvent.of(
            object.getId().toString(),
            version.getId().toString(),
            bucketId.toString(),
            principal.orgId(),
            principal.userId(),
            objectKey,
            storageKey,
            resolvedContentType,
            fileBytes.length,
            checksum));

    eventPublisher.publishAuditEvent(
        principal.orgId(),
        principal.userId(),
        "OBJECT_UPLOADED",
        "OBJECT_VERSION",
        version.getId().toString(),
        null,
        AuditEvent.AuditOutcome.SUCCESS);

    timer.stop(meterRegistry.timer("upload.singlepart.duration"));
    meterRegistry.counter("upload.files.total").increment();
    meterRegistry.counter("upload.bytes.total").increment(fileBytes.length);
    log.info(
        "Upload complete: objectId={} versionId={} size={} dedup={}",
        object.getId(),
        version.getId(),
        fileBytes.length,
        isDuplicate);

    return UploadResponse.builder()
        .objectId(object.getId())
        .versionId(version.getId())
        .objectKey(objectKey)
        .storageKey(storageKey)
        .sizeBytes(fileBytes.length)
        .checksumSha256(checksum)
        .etag(etag)
        .contentType(resolvedContentType)
        .isDuplicate(isDuplicate)
        .uploadedAt(version.getCreatedAt())
        .build();
  }

  @Transactional
  public InitiateUploadResponse initiateMultipartUpload(
      InitiateUploadRequest request, VaultFlowUserPrincipal principal) {

    validateBucketAccess(request.bucketId(), principal.orgId());

    if (request.expectedSize() != null) {
      quotaService.assertQuota(UUID.fromString(principal.orgId()), request.expectedSize());
    }

    UploadSession session =
        sessionRepository.save(
            UploadSession.builder()
                .bucketId(request.bucketId())
                .objectKey(request.objectKey())
                .orgId(UUID.fromString(principal.orgId()))
                .initiatedBy(UUID.fromString(principal.userId()))
                .contentType(
                    request.contentType() != null
                        ? request.contentType()
                        : "application/octet-stream")
                .expectedSize(request.expectedSize())
                .totalParts(request.totalParts())
                .status(UploadStatus.INITIATED)
                .build());

    log.info(
        "Multipart upload initiated: sessionId={} key={}", session.getId(), request.objectKey());
    return InitiateUploadResponse.builder()
        .sessionId(session.getId())
        .objectKey(request.objectKey())
        .bucketId(request.bucketId())
        .expiresAt(session.getExpiresAt())
        .build();
  }

  @Transactional
  public PartUploadResponse uploadPart(
      UUID sessionId,
      int partNumber,
      InputStream data,
      long partSize,
      String checksum,
      VaultFlowUserPrincipal principal) {

    if (partNumber < 1 || partNumber > 10000) {
      throw new InvalidUploadException("Part number must be between 1 and 10000");
    }
    if (partSize < 5 * 1024 * 1024 && partNumber > 1) {
      throw new InvalidUploadException("Part size must be at least 5 MB (except last part)");
    }

    UploadSession session = getActiveSession(sessionId, principal.orgId());

    byte[] partBytes;
    try {
      partBytes = IOUtils.toByteArray(data);
    } catch (IOException e) {
      throw new InvalidUploadException("Failed to read part data");
    }

    String actualChecksum = ChecksumUtil.sha256Hex(partBytes);
    if (checksum != null && !checksum.isBlank()) {
      ChecksumUtil.verify(checksum.toLowerCase(), actualChecksum);
    }

    String md5 = DigestUtils.md5Hex(partBytes);
    String partKey =
        storage.storePart(
            sessionId.toString(),
            partNumber,
            new ByteArrayInputStream(partBytes),
            partBytes.length);

    partRepository
        .findBySessionIdAndPartNumber(sessionId, partNumber)
        .ifPresentOrElse(
            existing -> {
              existing.setStorageKey(partKey);
              existing.setSizeBytes((long) partBytes.length);
              existing.setChecksumSha256(actualChecksum);
              existing.setChecksumMd5(md5);
              partRepository.save(existing);
            },
            () ->
                partRepository.save(
                    UploadPart.builder()
                        .sessionId(sessionId)
                        .partNumber(partNumber)
                        .storageKey(partKey)
                        .sizeBytes((long) partBytes.length)
                        .checksumMd5(md5)
                        .checksumSha256(actualChecksum)
                        .build()));

    session.setStatus(UploadStatus.UPLOADING);
    sessionRepository.save(session);

    long receivedParts = partRepository.countBySessionId(sessionId);
    return PartUploadResponse.builder()
        .sessionId(sessionId)
        .partNumber(partNumber)
        .sizeBytes((long) partBytes.length)
        .checksumSha256(actualChecksum)
        .etag('"' + md5 + '"')
        .receivedPartsCount(receivedParts)
        .build();
  }

  @Transactional
  public UploadResponse completeMultipartUpload(
      UUID sessionId, CompleteUploadRequest request, VaultFlowUserPrincipal principal) {

    String lockKey = LOCK_PREFIX + sessionId;
    Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(60));
    if (!Boolean.TRUE.equals(locked)) {
      throw new VaultFlowException(
          "Upload completion already in progress", HttpStatus.CONFLICT, "COMPLETION_IN_PROGRESS");
    }
    try {
      return doComplete(sessionId, request, principal);
    } finally {
      redisTemplate.delete(lockKey);
    }
  }

  private UploadResponse doComplete(
      UUID sessionId, CompleteUploadRequest request, VaultFlowUserPrincipal principal) {

    UploadSession session = getActiveSession(sessionId, principal.orgId());
    List<UploadPart> parts = partRepository.findBySessionIdOrderByPartNumberAsc(sessionId);

    if (parts.isEmpty())
      throw new InvalidUploadException("No parts uploaded for session: " + sessionId);

    if (request.partNumbers() != null) {
      Set<Integer> received =
          parts.stream().map(UploadPart::getPartNumber).collect(Collectors.toSet());
      for (int expected : request.partNumbers()) {
        if (!received.contains(expected))
          throw new InvalidUploadException("Missing part: " + expected);
      }
    }

    session.setStatus(UploadStatus.COMPLETING);
    sessionRepository.save(session);

    String finalChecksum = computeMultipartChecksum(parts);
    String storageKey = ChecksumUtil.toStoragePath(finalChecksum);
    List<String> partKeys = parts.stream().map(UploadPart::getStorageKey).toList();
    long totalSize = storage.assembleParts(storageKey, sessionId.toString(), partKeys);

    StoredObject object =
        objectRepository
            .findByBucketIdAndObjectKeyAndIsDeletedFalse(
                session.getBucketId(), session.getObjectKey())
            .orElseGet(
                () ->
                    objectRepository.save(
                        StoredObject.builder()
                            .bucketId(session.getBucketId())
                            .objectKey(session.getObjectKey())
                            .contentType(session.getContentType())
                            .build()));

    if (object.getCurrentVersionId() != null) versionRepository.markAllNotLatest(object.getId());

    int nextVersion =
        versionRepository.findMaxVersionNumber(object.getId()).map(n -> n + 1).orElse(1);
    String etag =
        '"'
            + EtagUtil.multipartEtag(parts.stream().map(UploadPart::getChecksumMd5).toList())
            + "-"
            + parts.size()
            + '"';

    ObjectVersion version =
        versionRepository.save(
            ObjectVersion.builder()
                .objectId(object.getId())
                .storageKey(storageKey)
                .sizeBytes(totalSize)
                .checksumSha256(finalChecksum)
                .etag(etag)
                .versionNumber(nextVersion)
                .contentType(session.getContentType())
                .isLatest(true)
                .uploadedBy(UUID.fromString(principal.userId()))
                .build());

    object.setCurrentVersionId(version.getId());
    objectRepository.save(object);
    session.setStatus(UploadStatus.COMPLETED);
    session.setObjectId(object.getId());
    session.setVersionId(version.getId());
    sessionRepository.save(session);

    quotaService.consumeQuota(UUID.fromString(principal.orgId()), totalSize);
    eventPublisher.publishFileUploaded(
        FileUploadedEvent.of(
            object.getId().toString(),
            version.getId().toString(),
            session.getBucketId().toString(),
            principal.orgId(),
            principal.userId(),
            session.getObjectKey(),
            storageKey,
            session.getContentType(),
            totalSize,
            finalChecksum));

    meterRegistry.counter("upload.multipart.completed").increment();
    meterRegistry.counter("upload.bytes.total").increment(totalSize);
    log.info(
        "Multipart complete: sessionId={} parts={} size={}", sessionId, parts.size(), totalSize);

    return UploadResponse.builder()
        .objectId(object.getId())
        .versionId(version.getId())
        .objectKey(session.getObjectKey())
        .storageKey(storageKey)
        .sizeBytes(totalSize)
        .checksumSha256(finalChecksum)
        .etag(etag)
        .contentType(session.getContentType())
        .uploadedAt(version.getCreatedAt())
        .build();
  }

  @Transactional
  public void abortUpload(UUID sessionId, VaultFlowUserPrincipal principal) {
    UploadSession session = getActiveSession(sessionId, principal.orgId());
    List<UploadPart> parts = partRepository.findBySessionIdOrderByPartNumberAsc(sessionId);
    parts.forEach(
        p -> storage.deletePart(sessionId.toString(), p.getPartNumber(), p.getStorageKey()));
    partRepository.deleteBySessionId(sessionId);
    session.setStatus(UploadStatus.ABORTED);
    sessionRepository.save(session);
    log.info("Upload aborted: sessionId={}", sessionId);
  }

  @Transactional(readOnly = true)
  public UploadStatusResponse getUploadStatus(UUID sessionId, VaultFlowUserPrincipal principal) {
    UploadSession session =
        sessionRepository
            .findByIdAndOrgId(sessionId, UUID.fromString(principal.orgId()))
            .orElseThrow(
                () -> new ResourceNotFoundException("UploadSession", sessionId.toString()));
    List<UploadPart> parts = partRepository.findBySessionIdOrderByPartNumberAsc(sessionId);
    return UploadStatusResponse.builder()
        .sessionId(sessionId)
        .status(session.getStatus().name())
        .objectKey(session.getObjectKey())
        .bucketId(session.getBucketId())
        .totalParts(session.getTotalParts())
        .receivedParts(parts.size())
        .receivedPartNumbers(parts.stream().map(UploadPart::getPartNumber).toList())
        .expiresAt(session.getExpiresAt())
        .build();
  }

  @Transactional
  public void softDeleteObject(UUID bucketId, String objectKey, VaultFlowUserPrincipal principal) {
    validateBucketAccess(bucketId, principal.orgId());
    StoredObject object =
        objectRepository
            .findByBucketIdAndObjectKeyAndIsDeletedFalse(bucketId, objectKey)
            .orElseThrow(() -> new ResourceNotFoundException("Object", objectKey));
    object.softDelete();
    objectRepository.save(object);
    eventPublisher.publishAuditEvent(
        principal.orgId(),
        principal.userId(),
        "OBJECT_DELETED",
        "OBJECT",
        object.getId().toString(),
        null,
        AuditEvent.AuditOutcome.SUCCESS);
  }

  @Transactional
  public void restoreObject(UUID bucketId, String objectKey, VaultFlowUserPrincipal principal) {
    validateBucketAccess(bucketId, principal.orgId());
    objectRepository
        .findByBucketIdAndObjectKeyAndIsDeletedFalse(bucketId, objectKey)
        .ifPresent(
            obj -> {
              obj.restore();
              objectRepository.save(obj);
            });
  }

  private Bucket validateBucketAccess(UUID bucketId, String orgId) {
    return bucketRepository
        .findByIdAndOrgId(bucketId, UUID.fromString(orgId))
        .orElseThrow(() -> new ResourceNotFoundException("Bucket", bucketId.toString()));
  }

  private UploadSession getActiveSession(UUID sessionId, String orgId) {
    UploadSession session =
        sessionRepository
            .findByIdAndOrgId(sessionId, UUID.fromString(orgId))
            .orElseThrow(
                () -> new ResourceNotFoundException("UploadSession", sessionId.toString()));
    if (!session.isActive())
      throw new InvalidUploadException("Session not active: " + session.getStatus());
    if (session.isExpired()) throw new InvalidUploadException("Upload session has expired");
    return session;
  }

  private String computeMultipartChecksum(List<UploadPart> parts) {
    StringBuilder sb = new StringBuilder();
    parts.forEach(p -> sb.append(p.getChecksumSha256()));
    return ChecksumUtil.sha256Hex(sb.toString());
  }
}
