package com.vaultflow.download.service;

import com.vaultflow.common.exception.ResourceNotFoundException;
import com.vaultflow.common.exception.VaultFlowAccessDeniedException;
import com.vaultflow.common.exception.VaultFlowException;
import com.vaultflow.common.security.VaultFlowUserPrincipal;
import com.vaultflow.download.domain.entity.ObjectVersionView;
import com.vaultflow.download.domain.entity.SignedUrlRecord;
import com.vaultflow.download.domain.repository.ObjectVersionViewRepository;
import com.vaultflow.download.domain.repository.SignedUrlRepository;
import com.vaultflow.download.dto.response.DownloadResponses.SignedUrlResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DownloadService {

  private final ObjectVersionViewRepository versionRepository;
  private final SignedUrlRepository signedUrlRepository;
  private final StorageReadPort storageReader;
  private final StringRedisTemplate redisTemplate;
  private final MeterRegistry meterRegistry;

  @Value("${vaultflow.signed-url.secret}")
  private String signedUrlSecret;

  @Value("${vaultflow.download.base-url:http://localhost:8083}")
  private String baseUrl;

  // ============================================================
  // Authenticated download
  // ============================================================

  public InputStream streamObject(
      UUID bucketId, String objectKey, VaultFlowUserPrincipal principal) {
    ObjectVersionView version = resolveCurrentVersion(bucketId, objectKey, principal.orgId());
    guardAgainstInfected(version);
    meterRegistry.counter("download.objects.total").increment();
    meterRegistry.counter("download.bytes.total").increment(version.sizeBytes());
    log.debug("Streaming object: key={} size={}", objectKey, version.sizeBytes());
    return storageReader.retrieve(version.storageKey());
  }

  public InputStream streamRange(
      UUID bucketId, String objectKey, long offset, long length, VaultFlowUserPrincipal principal) {
    ObjectVersionView version = resolveCurrentVersion(bucketId, objectKey, principal.orgId());
    guardAgainstInfected(version);
    meterRegistry.counter("download.range.total").increment();
    return storageReader.retrieveRange(version.storageKey(), offset, length);
  }

  public ObjectVersionView getObjectMetadata(
      UUID bucketId, String objectKey, VaultFlowUserPrincipal principal) {
    return resolveCurrentVersion(bucketId, objectKey, principal.orgId());
  }

  // ============================================================
  // Signed URL generation
  // ============================================================

  @Transactional
  public SignedUrlResponse generateSignedUrl(
      UUID objectVersionId,
      long ttlSeconds,
      Integer maxDownloads,
      String allowedIp,
      VaultFlowUserPrincipal principal) {

    ObjectVersionView version =
        versionRepository
            .findById(objectVersionId)
            .orElseThrow(
                () -> new ResourceNotFoundException("ObjectVersion", objectVersionId.toString()));

    if (!version.orgId().equals(UUID.fromString(principal.orgId()))) {
      throw new VaultFlowAccessDeniedException(
          "Object version does not belong to your organization");
    }

    Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
    String tokenInput =
        objectVersionId
            + ":"
            + expiresAt.getEpochSecond()
            + ":"
            + (allowedIp != null ? allowedIp : "*");
    String token = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, signedUrlSecret).hmacHex(tokenInput);

    SignedUrlRecord record =
        signedUrlRepository.save(
            SignedUrlRecord.builder()
                .objectVersionId(objectVersionId)
                .token(token)
                .expiresAt(expiresAt)
                .maxDownloads(maxDownloads)
                .downloadCount(0)
                .allowedIp(allowedIp)
                .createdBy(UUID.fromString(principal.userId()))
                .build());

    String downloadUrl =
        baseUrl
            + "/api/v1/download/signed?token="
            + token
            + "&expires="
            + expiresAt.getEpochSecond();

    log.info("Signed URL created: versionId={} expiresAt={}", objectVersionId, expiresAt);

    return SignedUrlResponse.builder()
        .id(record.getId())
        .url(downloadUrl)
        .token(token)
        .expiresAt(expiresAt)
        .maxDownloads(maxDownloads)
        .createdAt(record.getCreatedAt())
        .build();
  }

  // ============================================================
  // Signed URL download (unauthenticated)
  // ============================================================

  @Transactional
  public InputStream streamSignedUrl(String token, String clientIp) {
    SignedUrlRecord record =
        signedUrlRepository
            .findByToken(token)
            .orElseThrow(
                () ->
                    new VaultFlowException(
                        "Invalid signed URL", HttpStatus.FORBIDDEN, "INVALID_TOKEN"));

    if (record.getExpiresAt().isBefore(Instant.now())) {
      throw new VaultFlowException("Signed URL has expired", HttpStatus.GONE, "URL_EXPIRED");
    }

    if (record.getMaxDownloads() != null && record.getDownloadCount() >= record.getMaxDownloads()) {
      throw new VaultFlowException(
          "Download limit exceeded", HttpStatus.FORBIDDEN, "DOWNLOAD_LIMIT_EXCEEDED");
    }

    if (record.getAllowedIp() != null && !record.getAllowedIp().equals(clientIp)) {
      throw new VaultFlowException("IP not allowed", HttpStatus.FORBIDDEN, "IP_RESTRICTED");
    }

    signedUrlRepository.incrementDownloadCount(record.getId());

    // Cache valid token in Redis to reduce DB reads on popular files
    String cacheKey = "signedurl:valid:" + token;
    redisTemplate
        .opsForValue()
        .set(cacheKey, record.getObjectVersionId().toString(), 30, TimeUnit.SECONDS);

    ObjectVersionView version =
        versionRepository
            .findById(record.getObjectVersionId())
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "ObjectVersion", record.getObjectVersionId().toString()));

    guardAgainstInfected(version);
    meterRegistry.counter("download.signed.total").increment();
    return storageReader.retrieve(version.storageKey());
  }

  // ============================================================
  // Helpers
  // ============================================================

  private ObjectVersionView resolveCurrentVersion(UUID bucketId, String objectKey, String orgId) {
    return versionRepository
        .findCurrentVersionByBucketAndKey(bucketId, objectKey, UUID.fromString(orgId))
        .orElseThrow(() -> new ResourceNotFoundException("Object", objectKey));
  }

  private void guardAgainstInfected(ObjectVersionView version) {
    if (version.isInfected()) {
      throw new VaultFlowException(
          "Object has been flagged by virus scan and cannot be downloaded",
          HttpStatus.FORBIDDEN,
          "INFECTED_FILE");
    }
  }
}
