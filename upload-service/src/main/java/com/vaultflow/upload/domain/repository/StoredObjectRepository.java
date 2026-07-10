package com.vaultflow.upload.domain.repository;

import com.vaultflow.upload.domain.entity.StoredObject;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StoredObjectRepository extends JpaRepository<StoredObject, UUID> {

  Optional<StoredObject> findByBucketIdAndObjectKeyAndIsDeletedFalse(UUID bucketId, String key);

  @Query("SELECT o FROM StoredObject o WHERE o.bucketId = :bucketId AND o.isDeleted = false"
      + " AND (:prefix IS NULL OR o.objectKey LIKE :prefix%)")
  Page<StoredObject> listObjects(@Param("bucketId") UUID bucketId,
      @Param("prefix") String prefix, Pageable pageable);

  @Query("SELECT COUNT(o) FROM StoredObject o WHERE o.bucketId = :bucketId AND o.isDeleted = false")
  long countByBucketId(@Param("bucketId") UUID bucketId);
}
