package com.vaultflow.upload.domain.repository;

import com.vaultflow.upload.domain.entity.Bucket;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BucketRepository extends JpaRepository<Bucket, UUID> {
  Optional<Bucket> findByIdAndOrgId(UUID id, UUID orgId);

  Optional<Bucket> findByNameAndOrgId(String name, UUID orgId);

  boolean existsByNameAndOrgId(String name, UUID orgId);
}
