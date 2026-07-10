package com.vaultflow.upload.service;

import com.vaultflow.common.exception.ConflictException;
import com.vaultflow.common.exception.ResourceNotFoundException;
import com.vaultflow.common.security.VaultFlowUserPrincipal;
import com.vaultflow.upload.domain.entity.Bucket;
import com.vaultflow.upload.domain.repository.BucketRepository;
import com.vaultflow.upload.dto.request.UploadRequests.CreateBucketRequest;
import com.vaultflow.upload.dto.response.UploadResponses.BucketResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BucketService {

  private final BucketRepository bucketRepository;

  @Transactional
  public BucketResponse createBucket(CreateBucketRequest req, VaultFlowUserPrincipal principal) {
    UUID orgId = UUID.fromString(principal.orgId());

    if (bucketRepository.existsByNameAndOrgId(req.name(), orgId)) {
      throw new ConflictException("Bucket already exists: " + req.name());
    }

    Bucket bucket = bucketRepository.save(Bucket.builder()
        .orgId(orgId)
        .name(req.name())
        .region(req.region() != null ? req.region() : "ap-south-1")
        .versioningEnabled(Boolean.TRUE.equals(req.versioningEnabled()))
        .build());

    log.info("Bucket created: bucketId={} name={} orgId={}", bucket.getId(), bucket.getName(), orgId);
    return toResponse(bucket);
  }

  @Transactional(readOnly = true)
  public List<BucketResponse> listBuckets(VaultFlowUserPrincipal principal) {
    UUID orgId = UUID.fromString(principal.orgId());
    return bucketRepository.findAll().stream()
        .filter(b -> b.getOrgId().equals(orgId) && "ACTIVE".equals(b.getStatus()))
        .map(this::toResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public BucketResponse getBucket(UUID bucketId, VaultFlowUserPrincipal principal) {
    return toResponse(validateAndGetBucket(bucketId, principal.orgId()));
  }

  @Transactional
  public void deleteBucket(UUID bucketId, VaultFlowUserPrincipal principal) {
    Bucket bucket = validateAndGetBucket(bucketId, principal.orgId());
    bucket.setStatus("DELETED");
    bucketRepository.save(bucket);
    log.info("Bucket deleted: bucketId={}", bucketId);
  }

  private Bucket validateAndGetBucket(UUID bucketId, String orgId) {
    return bucketRepository.findByIdAndOrgId(bucketId, UUID.fromString(orgId))
        .orElseThrow(() -> new ResourceNotFoundException("Bucket", bucketId.toString()));
  }

  private BucketResponse toResponse(Bucket b) {
    return BucketResponse.builder()
        .id(b.getId()).orgId(b.getOrgId()).name(b.getName())
        .region(b.getRegion()).versioningEnabled(b.getVersioningEnabled())
        .status(b.getStatus()).createdAt(b.getCreatedAt()).build();
  }
}
