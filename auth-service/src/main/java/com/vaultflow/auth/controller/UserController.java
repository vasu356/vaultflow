package com.vaultflow.auth.controller;

import com.vaultflow.auth.dto.request.AuthRequests.InviteUserRequest;
import com.vaultflow.auth.dto.request.AuthRequests.UpdateUserRoleRequest;
import com.vaultflow.auth.dto.response.AuthResponses.MessageResponse;
import com.vaultflow.auth.dto.response.AuthResponses.UserResponse;
import com.vaultflow.auth.service.UserService;
import com.vaultflow.common.dto.PageResponse;
import com.vaultflow.common.security.VaultFlowUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User management within an organization")
public class UserController {

  private final UserService userService;

  @GetMapping
  @PreAuthorize("hasRole('ADMIN') or hasRole('OWNER')")
  @Operation(summary = "List all users in the organization")
  public ResponseEntity<PageResponse<UserResponse>> listUsers(
      @AuthenticationPrincipal VaultFlowUserPrincipal principal,
      @RequestParam("page") int page,
      @RequestParam("size") int size) {
    return ResponseEntity.ok(
        userService.listUsers(UUID.fromString(principal.orgId()), page, size));
  }

  @PostMapping("/invite")
  @PreAuthorize("hasRole('ADMIN') or hasRole('OWNER')")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Invite a new user to the organization")
  public ResponseEntity<UserResponse> inviteUser(
      @Valid @RequestBody InviteUserRequest request,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(userService.inviteUser(request, UUID.fromString(principal.orgId()), principal));
  }

  @PatchMapping("/{userId}/role")
  @PreAuthorize("hasRole('ADMIN') or hasRole('OWNER')")
  @Operation(summary = "Update a user's role")
  public ResponseEntity<UserResponse> updateRole(
      @PathVariable("userId") UUID userId,
      @Valid @RequestBody UpdateUserRoleRequest request,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.ok(
        userService.updateRole(userId, request, UUID.fromString(principal.orgId()), principal));
  }

  @DeleteMapping("/{userId}")
  @PreAuthorize("hasRole('ADMIN') or hasRole('OWNER')")
  @Operation(summary = "Deactivate a user from the organization")
  public ResponseEntity<MessageResponse> deactivateUser(
      @PathVariable("userId") UUID userId,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    userService.deactivateUser(userId, UUID.fromString(principal.orgId()), principal);
    return ResponseEntity.ok(MessageResponse.of("User deactivated"));
  }
}
