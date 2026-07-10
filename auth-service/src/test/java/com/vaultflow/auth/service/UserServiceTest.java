package com.vaultflow.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vaultflow.auth.domain.entity.Organization;
import com.vaultflow.auth.domain.entity.User;
import com.vaultflow.auth.domain.entity.User.UserStatus;
import com.vaultflow.auth.domain.enums.UserRole;
import com.vaultflow.auth.domain.repository.OrganizationRepository;
import com.vaultflow.auth.domain.repository.UserRepository;
import com.vaultflow.auth.dto.request.AuthRequests.UpdateUserRoleRequest;
import com.vaultflow.auth.dto.response.AuthResponses.UserResponse;
import com.vaultflow.common.exception.VaultFlowAccessDeniedException;
import com.vaultflow.common.security.VaultFlowUserPrincipal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService")
class UserServiceTest {

  @Mock UserRepository userRepository;
  @Mock OrganizationRepository orgRepository;

  UserService userService;
  UUID orgId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    userService = new UserService(userRepository, orgRepository, new BCryptPasswordEncoder(4));
  }

  private User makeUser(UUID id, UserRole role) {
    Organization org = Organization.builder().id(orgId).name("Test").slug("test").build();
    return User.builder()
        .id(id).organization(org).email("user@test.com").passwordHash("hash")
        .fullName("Test User").role(role).status(UserStatus.ACTIVE).emailVerified(true).build();
  }

  private VaultFlowUserPrincipal ownerPrincipal(UUID userId) {
    return new VaultFlowUserPrincipal(userId.toString(), "owner@test.com",
        orgId.toString(), "OWNER", List.of("read","write","delete","admin"), null);
  }

  private VaultFlowUserPrincipal adminPrincipal(UUID userId) {
    return new VaultFlowUserPrincipal(userId.toString(), "admin@test.com",
        orgId.toString(), "ADMIN", List.of("read","write","delete","admin"), null);
  }

  @Nested
  @DisplayName("updateRole")
  class UpdateRole {

    @Test
    @DisplayName("OWNER can promote VIEWER to EDITOR")
    void ownerCanPromoteViewer() {
      UUID targetId = UUID.randomUUID();
      UUID actorId = UUID.randomUUID();
      User target = makeUser(targetId, UserRole.VIEWER);

      when(userRepository.findByIdAndOrganizationId(targetId, orgId)).thenReturn(Optional.of(target));
      when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      UserResponse result = userService.updateRole(
          targetId, new UpdateUserRoleRequest("EDITOR"), orgId, ownerPrincipal(actorId));

      assertThat(result.role()).isEqualTo("EDITOR");
    }

    @Test
    @DisplayName("ADMIN cannot assign OWNER role")
    void adminCannotAssignOwnerRole() {
      UUID targetId = UUID.randomUUID();
      UUID actorId = UUID.randomUUID();
      User target = makeUser(targetId, UserRole.EDITOR);

      when(userRepository.findByIdAndOrganizationId(targetId, orgId)).thenReturn(Optional.of(target));

      assertThatThrownBy(() -> userService.updateRole(
          targetId, new UpdateUserRoleRequest("OWNER"), orgId, adminPrincipal(actorId)))
          .isInstanceOf(VaultFlowAccessDeniedException.class)
          .hasMessageContaining("OWNER");
    }

    @Test
    @DisplayName("cannot change own role")
    void cannotChangeOwnRole() {
      UUID actorId = UUID.randomUUID();
      User self = makeUser(actorId, UserRole.ADMIN);

      when(userRepository.findByIdAndOrganizationId(actorId, orgId)).thenReturn(Optional.of(self));

      assertThatThrownBy(() -> userService.updateRole(
          actorId, new UpdateUserRoleRequest("VIEWER"), orgId, adminPrincipal(actorId)))
          .isInstanceOf(VaultFlowAccessDeniedException.class)
          .hasMessageContaining("own role");
    }

    @Test
    @DisplayName("cannot demote OWNER without transfer")
    void cannotDemoteOwner() {
      UUID ownerId = UUID.randomUUID();
      UUID actorId = UUID.randomUUID();
      User owner = makeUser(ownerId, UserRole.OWNER);

      when(userRepository.findByIdAndOrganizationId(ownerId, orgId)).thenReturn(Optional.of(owner));

      assertThatThrownBy(() -> userService.updateRole(
          ownerId, new UpdateUserRoleRequest("ADMIN"), orgId, ownerPrincipal(actorId)))
          .isInstanceOf(VaultFlowAccessDeniedException.class)
          .hasMessageContaining("owner");
    }
  }

  @Nested
  @DisplayName("deactivateUser")
  class DeactivateUser {

    @Test
    @DisplayName("marks user as SUSPENDED")
    void deactivatesUser() {
      UUID targetId = UUID.randomUUID();
      UUID actorId = UUID.randomUUID();
      User target = makeUser(targetId, UserRole.EDITOR);

      when(userRepository.findByIdAndOrganizationId(targetId, orgId)).thenReturn(Optional.of(target));
      when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      userService.deactivateUser(targetId, orgId, ownerPrincipal(actorId));

      ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
      verify(userRepository).save(captor.capture());
      assertThat(captor.getValue().getStatus()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    @DisplayName("cannot deactivate the OWNER")
    void cannotDeactivateOwner() {
      UUID ownerId = UUID.randomUUID();
      UUID actorId = UUID.randomUUID();
      User owner = makeUser(ownerId, UserRole.OWNER);

      when(userRepository.findByIdAndOrganizationId(ownerId, orgId)).thenReturn(Optional.of(owner));

      assertThatThrownBy(() -> userService.deactivateUser(ownerId, orgId, ownerPrincipal(actorId)))
          .isInstanceOf(VaultFlowAccessDeniedException.class);
    }

    @Test
    @DisplayName("cannot deactivate yourself")
    void cannotDeactivateSelf() {
      UUID actorId = UUID.randomUUID();
      User self = makeUser(actorId, UserRole.ADMIN);

      when(userRepository.findByIdAndOrganizationId(actorId, orgId)).thenReturn(Optional.of(self));

      assertThatThrownBy(() -> userService.deactivateUser(actorId, orgId, adminPrincipal(actorId)))
          .isInstanceOf(VaultFlowAccessDeniedException.class);
    }
  }
}
