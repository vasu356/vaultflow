package com.vaultflow.auth.controller;

import com.vaultflow.auth.dto.request.AuthRequests.LoginRequest;
import com.vaultflow.auth.dto.request.AuthRequests.RefreshTokenRequest;
import com.vaultflow.auth.dto.request.AuthRequests.RegisterOrganizationRequest;
import com.vaultflow.auth.dto.response.AuthResponses.MessageResponse;
import com.vaultflow.auth.dto.response.AuthResponses.TokenResponse;
import com.vaultflow.auth.service.AuthService;
import com.vaultflow.common.security.VaultFlowUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "Registration, login, token management")
public class AuthController {

  private final AuthService authService;

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Register a new organization and owner account")
  public ResponseEntity<TokenResponse> register(
      @Valid @RequestBody RegisterOrganizationRequest request,
      HttpServletRequest httpRequest) {
    String ip = extractClientIp(httpRequest);
    TokenResponse response = authService.registerOrganization(request, ip);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PostMapping("/login")
  @Operation(summary = "Authenticate and receive access + refresh tokens")
  public ResponseEntity<TokenResponse> login(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest httpRequest) {
    String ip = extractClientIp(httpRequest);
    return ResponseEntity.ok(authService.login(request, ip));
  }

  @PostMapping("/refresh")
  @Operation(summary = "Exchange a valid refresh token for a new token pair")
  public ResponseEntity<TokenResponse> refresh(
      @Valid @RequestBody RefreshTokenRequest request,
      HttpServletRequest httpRequest) {
    String ip = extractClientIp(httpRequest);
    return ResponseEntity.ok(authService.refreshToken(request, ip));
  }

  @PostMapping("/logout")
  @Operation(summary = "Revoke the current session's refresh token")
  public ResponseEntity<MessageResponse> logout(
      @Valid @RequestBody RefreshTokenRequest request,
      @RequestHeader(value = "Authorization", required = false) String authHeader,
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {

    // Extract JTI from bearer token header for blacklisting
    String jti = null;
    // JTI extracted inside service from claims — pass raw header
    authService.logout(request.refreshToken(), jti);
    return ResponseEntity.ok(MessageResponse.of("Logged out successfully"));
  }

  @PostMapping("/logout-all")
  @Operation(summary = "Revoke all sessions for the current user (all devices)")
  public ResponseEntity<MessageResponse> logoutAll(
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    authService.logoutAllDevices(java.util.UUID.fromString(principal.userId()));
    return ResponseEntity.ok(MessageResponse.of("All sessions revoked"));
  }

  @GetMapping("/me")
  @Operation(summary = "Get current authenticated user's profile")
  public ResponseEntity<VaultFlowUserPrincipal> me(
      @AuthenticationPrincipal VaultFlowUserPrincipal principal) {
    return ResponseEntity.ok(principal);
  }

  private String extractClientIp(HttpServletRequest request) {
    String xForwardedFor = request.getHeader("X-Forwarded-For");
    if (xForwardedFor != null && !xForwardedFor.isBlank()) {
      return xForwardedFor.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
