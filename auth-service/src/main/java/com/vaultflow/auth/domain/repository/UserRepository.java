package com.vaultflow.auth.domain.repository;

import com.vaultflow.auth.domain.entity.User;
import com.vaultflow.auth.domain.entity.User.UserStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

  Optional<User> findByEmail(String email);

  @Query(
      "SELECT u FROM User u JOIN FETCH u.organization"
          + " WHERE u.email = :email AND u.status = :status")
  Optional<User> findActiveByEmail(
      @Param("email") String email, @Param("status") UserStatus status);

  Optional<User> findByIdAndOrganizationId(UUID id, UUID orgId);

  boolean existsByEmailAndOrganizationId(String email, UUID orgId);

  Page<User> findAllByOrganizationId(UUID orgId, Pageable pageable);

  @Query("SELECT COUNT(u) FROM User u WHERE u.organization.id = :orgId AND u.status = 'ACTIVE'")
  long countActiveByOrgId(@Param("orgId") UUID orgId);
}
