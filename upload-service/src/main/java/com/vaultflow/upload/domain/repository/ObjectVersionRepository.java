package com.vaultflow.upload.domain.repository;

import com.vaultflow.upload.domain.entity.ObjectVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ObjectVersionRepository extends JpaRepository<ObjectVersion, UUID> {

  Optional<ObjectVersion> findByObjectIdAndIsLatestTrue(UUID objectId);

  List<ObjectVersion> findByObjectIdOrderByVersionNumberDesc(UUID objectId);

  /** Deduplication check: does this content already exist in storage? */
  Optional<ObjectVersion> findFirstByStorageKey(String storageKey);

  @Query("SELECT MAX(v.versionNumber) FROM ObjectVersion v WHERE v.objectId = :objectId")
  Optional<Integer> findMaxVersionNumber(@Param("objectId") UUID objectId);

  /** Unmark all previous versions when a new one is uploaded. */
  @Modifying
  @Query("UPDATE ObjectVersion v SET v.isLatest = false WHERE v.objectId = :objectId AND v.isLatest = true")
  void markAllNotLatest(@Param("objectId") UUID objectId);

  @Query("SELECT COUNT(v) FROM ObjectVersion v WHERE v.storageKey = :storageKey")
  long countByStorageKey(@Param("storageKey") String storageKey);
}
