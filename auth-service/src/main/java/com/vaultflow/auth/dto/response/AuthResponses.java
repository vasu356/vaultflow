package com.vaultflow.auth.dto.response;

import java.time.Instant;
import java.util.UUID;

public final class AuthResponses {

  private AuthResponses() {}

  public record TokenResponse(
      String accessToken,
      String refreshToken,
      String tokenType,
      long accessTokenExpiresIn,
      UserResponse user) {

    public static TokenResponse of(
        String accessToken, String refreshToken, long accessTokenExpiresIn, UserResponse user) {
      return new TokenResponse(accessToken, refreshToken, "Bearer", accessTokenExpiresIn, user);
    }
  }

  public record UserResponse(
      UUID id,
      String email,
      String fullName,
      String role,
      UUID orgId,
      String orgSlug,
      String orgName,
      Boolean emailVerified,
      Instant createdAt,
      Instant lastLoginAt) {}

  public record OrganizationResponse(
      UUID id,
      String name,
      String slug,
      long quotaBytes,
      long usedBytes,
      long usedPercent,
      String status,
      Instant createdAt) {}

  public record MessageResponse(String message) {
    public static MessageResponse of(String message) {
      return new MessageResponse(message);
    }
  }
}
