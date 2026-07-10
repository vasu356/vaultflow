package com.vaultflow.upload.domain.repository;

import com.vaultflow.upload.domain.entity.UploadPart;
import com.vaultflow.upload.domain.entity.UploadSession;
import com.vaultflow.upload.domain.enums.UploadStatus;
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
public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {

  Optional<UploadSession> findByIdAndOrgId(UUID id, UUID orgId);

  @Modifying
  @Query("UPDATE UploadSession s SET s.status = 'EXPIRED' WHERE s.expiresAt < :now"
      + " AND s.status IN ('INITIATED', 'UPLOADING')")
  int expireStaleSessions(@Param("now") Instant now);

  List<UploadSession> findByStatusIn(List<UploadStatus> statuses);
}

