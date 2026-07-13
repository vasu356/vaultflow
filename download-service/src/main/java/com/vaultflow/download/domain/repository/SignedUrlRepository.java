package com.vaultflow.download.domain.repository;

import com.vaultflow.download.domain.entity.SignedUrlRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SignedUrlRepository extends JpaRepository<SignedUrlRecord, UUID> {

  Optional<SignedUrlRecord> findByToken(String token);

  @Modifying
  @Query("UPDATE SignedUrlRecord s SET s.downloadCount = s.downloadCount + 1 WHERE s.id = :id")
  void incrementDownloadCount(@Param("id") UUID id);
}
