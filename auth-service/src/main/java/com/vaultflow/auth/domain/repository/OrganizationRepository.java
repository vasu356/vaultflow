package com.vaultflow.auth.domain.repository;

import com.vaultflow.auth.domain.entity.Organization;
import com.vaultflow.auth.domain.entity.Organization.OrgStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

  Optional<Organization> findBySlug(String slug);

  Optional<Organization> findByIdAndStatus(UUID id, OrgStatus status);

  boolean existsBySlug(String slug);

  /**
   * Atomically update used_bytes with a delta. Uses SQL-level update rather than loading the entity
   * to avoid race conditions under concurrent uploads. Multiple uploads can safely call this
   * concurrently — PostgreSQL row-level locking ensures correctness.
   */
  @Modifying
  @Query(
      "UPDATE Organization o SET o.usedBytes = o.usedBytes + :delta WHERE o.id = :orgId"
          + " AND o.usedBytes + :delta >= 0")
  int updateUsedBytes(@Param("orgId") UUID orgId, @Param("delta") long delta);

  /** Fetch quota status efficiently — avoids loading full org for hot-path quota checks. */
  @Query("SELECT o.quotaBytes - o.usedBytes FROM Organization o WHERE o.id = :orgId")
  Optional<Long> findRemainingQuota(@Param("orgId") UUID orgId);
}
