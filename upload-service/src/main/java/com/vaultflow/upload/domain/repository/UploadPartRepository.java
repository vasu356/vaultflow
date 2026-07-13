package com.vaultflow.upload.domain.repository;

import com.vaultflow.upload.domain.entity.UploadPart;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UploadPartRepository extends JpaRepository<UploadPart, UUID> {

  List<UploadPart> findBySessionIdOrderByPartNumberAsc(UUID sessionId);

  Optional<UploadPart> findBySessionIdAndPartNumber(UUID sessionId, int partNumber);

  long countBySessionId(UUID sessionId);

  void deleteBySessionId(UUID sessionId);
}
