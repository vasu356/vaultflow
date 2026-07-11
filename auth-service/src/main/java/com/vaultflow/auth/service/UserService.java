package com.vaultflow.auth.service;

import com.vaultflow.auth.domain.entity.Organization;
import com.vaultflow.auth.domain.entity.User;
import com.vaultflow.auth.domain.entity.User.UserStatus;
import com.vaultflow.auth.domain.enums.UserRole;
import com.vaultflow.auth.domain.repository.OrganizationRepository;
import com.vaultflow.auth.domain.repository.UserRepository;
import com.vaultflow.auth.dto.request.AuthRequests.InviteUserRequest;
import com.vaultflow.auth.dto.request.AuthRequests.UpdateUserRoleRequest;
import com.vaultflow.auth.dto.response.AuthResponses.UserResponse;
import com.vaultflow.common.dto.PageResponse;
import com.vaultflow.common.exception.ConflictException;
import com.vaultflow.common.exception.ResourceNotFoundException;
import com.vaultflow.common.exception.VaultFlowAccessDeniedException;
import com.vaultflow.common.security.VaultFlowUserPrincipal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

  private final UserRepository userRepository;
  private final OrganizationRepository orgRepository;
  private final PasswordEncoder passwordEncoder;

  @Transactional(readOnly = true)
  public PageResponse<UserResponse> listUsers(UUID orgId, int page, int size) {
    Page<User> users = userRepository.findAllByOrganizationId(orgId, PageRequest.of(page, size));
    return PageResponse.of(
        users.getContent().stream().map(this::toResponse).toList(),
        page,
        size,
        users.getTotalElements());
  }

  @Transactional
  public UserResponse inviteUser(
      InviteUserRequest request, UUID orgId, VaultFlowUserPrincipal inviter) {

    UserRole requestedRole = parseRole(request.role());

    // ADMIN cannot create OWNER — only OWNER can promote to OWNER
    if (requestedRole == UserRole.OWNER && !inviter.isOwner()) {
      throw new VaultFlowAccessDeniedException("Only the organization owner can assign OWNER role");
    }

    // EDITOR can only be created by ADMIN or higher
    if (requestedRole.getLevel() >= UserRole.ADMIN.getLevel() && !inviter.isAdmin()) {
      throw new VaultFlowAccessDeniedException("Insufficient permissions to assign this role");
    }

    if (userRepository.existsByEmailAndOrganizationId(request.email().toLowerCase(), orgId)) {
      throw new ConflictException("User already exists in this organization: " + request.email());
    }

    Organization org =
        orgRepository
            .findById(orgId)
            .orElseThrow(() -> new ResourceNotFoundException("Organization", orgId.toString()));

    // Invited users get a temporary password — in production, send email verification link
    String tempPassword = UUID.randomUUID().toString();
    User user =
        User.builder()
            .organization(org)
            .email(request.email().toLowerCase())
            .passwordHash(passwordEncoder.encode(tempPassword))
            .fullName(request.fullName())
            .role(requestedRole)
            .status(UserStatus.PENDING_VERIFICATION)
            .emailVerified(false)
            .build();

    user = userRepository.save(user);
    log.info(
        "User invited: userId={} email={} orgId={} role={}",
        user.getId(),
        user.getEmail(),
        orgId,
        requestedRole);

    return toResponse(user);
  }

  @Transactional
  public UserResponse updateRole(
      UUID userId, UpdateUserRoleRequest request, UUID orgId, VaultFlowUserPrincipal actor) {

    User user =
        userRepository
            .findByIdAndOrganizationId(userId, orgId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId.toString()));

    UserRole newRole = parseRole(request.role());

    // Cannot demote OWNER without transferring ownership
    if (user.getRole() == UserRole.OWNER && newRole != UserRole.OWNER) {
      throw new VaultFlowAccessDeniedException(
          "Cannot demote organization owner. Transfer ownership first.");
    }

    // ADMIN cannot promote to OWNER
    if (newRole == UserRole.OWNER && !actor.isOwner()) {
      throw new VaultFlowAccessDeniedException("Only organization owner can assign OWNER role");
    }

    // Cannot change own role
    if (user.getId().toString().equals(actor.userId())) {
      throw new VaultFlowAccessDeniedException("Cannot change your own role");
    }

    user.setRole(newRole);
    user = userRepository.save(user);
    log.info("User role updated: userId={} newRole={} by={}", userId, newRole, actor.userId());

    return toResponse(user);
  }

  @Transactional
  public void deactivateUser(UUID userId, UUID orgId, VaultFlowUserPrincipal actor) {
    User user =
        userRepository
            .findByIdAndOrganizationId(userId, orgId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId.toString()));

    if (user.getRole() == UserRole.OWNER) {
      throw new VaultFlowAccessDeniedException("Cannot deactivate organization owner");
    }

    if (user.getId().toString().equals(actor.userId())) {
      throw new VaultFlowAccessDeniedException("Cannot deactivate your own account");
    }

    user.setStatus(UserStatus.SUSPENDED);
    userRepository.save(user);
    log.info("User deactivated: userId={} by={}", userId, actor.userId());
  }

  private UserRole parseRole(String role) {
    try {
      return UserRole.valueOf(role.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new com.vaultflow.common.exception.VaultFlowException(
          "Invalid role: " + role + ". Valid values: OWNER, ADMIN, EDITOR, VIEWER",
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "INVALID_ROLE");
    }
  }

  public UserResponse toResponse(User user) {
    Organization org = user.getOrganization();
    return new UserResponse(
        user.getId(),
        user.getEmail(),
        user.getFullName(),
        user.getRole().name(),
        org != null ? org.getId() : null,
        org != null ? org.getSlug() : null,
        org != null ? org.getName() : null,
        user.getEmailVerified(),
        user.getCreatedAt(),
        user.getLastLoginAt());
  }
}
