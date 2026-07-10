package com.vaultflow.auth.domain.repository;

import com.vaultflow.auth.domain.entity.RefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /** Revoke all tokens in a rotation family (used when token reuse detected = theft). */
  @Modifying
  @Query(
      "UPDATE RefreshToken t SET t.revoked = true, t.revokedAt = :now, t.revokeReason = :reason"
          + " WHERE t.familyId = :familyId")
  void revokeFamily(
      @Param("familyId") UUID familyId, @Param("now") Instant now, @Param("reason") String reason);

  /** Revoke all active tokens for a user (logout all devices). */
  @Modifying
  @Query(
      "UPDATE RefreshToken t SET t.revoked = true, t.revokedAt = :now, t.revokeReason = 'LOGOUT_ALL'"
          + " WHERE t.user.id = :userId AND t.revoked = false")
  void revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

  /** Cleanup job: delete expired tokens older than 30 days. */
  @Modifying
  @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :cutoff")
  int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

  List<RefreshToken> findByFamilyId(UUID familyId);
}
