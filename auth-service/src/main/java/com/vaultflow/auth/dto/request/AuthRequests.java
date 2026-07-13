package com.vaultflow.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthRequests {

  private AuthRequests() {}

  public record RegisterOrganizationRequest(
      @NotBlank @Size(min = 2, max = 100) String organizationName,
      @NotBlank
          @Pattern(
              regexp = "^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$",
              message = "Slug must be lowercase alphanumeric with hyphens, 3-63 chars")
          String organizationSlug,
      @NotBlank @Size(min = 2, max = 100) String fullName,
      @NotBlank @Email String email,
      @NotBlank
          @Size(min = 8, max = 128)
          @Pattern(
              regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$",
              message = "Password must contain uppercase, lowercase, number and special character")
          String password) {}

  public record LoginRequest(
      @NotBlank @Email String email, @NotBlank @Size(min = 1, max = 128) String password) {}

  public record RefreshTokenRequest(@NotBlank String refreshToken) {}

  public record ChangePasswordRequest(
      @NotBlank String currentPassword,
      @NotBlank
          @Size(min = 8, max = 128)
          @Pattern(
              regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$",
              message = "Password must contain uppercase, lowercase, number and special character")
          String newPassword) {}

  public record InviteUserRequest(
      @NotBlank @Email String email,
      @NotBlank @Size(min = 2, max = 100) String fullName,
      @NotBlank String role) {}

  public record UpdateUserRoleRequest(@NotBlank String role) {}
}
