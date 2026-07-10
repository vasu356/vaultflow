package com.vaultflow.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vaultflow.auth.domain.entity.Organization;
import com.vaultflow.auth.domain.entity.RefreshToken;
import com.vaultflow.auth.domain.entity.User;
import com.vaultflow.auth.domain.entity.User.UserStatus;
import com.vaultflow.auth.domain.enums.UserRole;
import com.vaultflow.auth.domain.repository.OrganizationRepository;
import com.vaultflow.auth.domain.repository.RefreshTokenRepository;
import com.vaultflow.auth.domain.repository.UserRepository;
import com.vaultflow.auth.dto.request.AuthRequests.LoginRequest;
import com.vaultflow.auth.dto.request.AuthRequests.RegisterOrganizationRequest;
import com.vaultflow.auth.dto.response.AuthResponses.TokenResponse;
import com.vaultflow.common.exception.ConflictException;
import com.vaultflow.common.exception.VaultFlowException;
import com.vaultflow.common.security.JwtTokenProvider;
import com.vaultflow.common.util.ChecksumUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

  @Mock private OrganizationRepository orgRepository;
  @Mock private UserRepository userRepository;
  @Mock private RefreshTokenRepository refreshTokenRepository;
  @Mock private JwtTokenProvider jwtTokenProvider;
  @Mock private TokenRevocationService revocationService;

  private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4); // cost 4 = fast in tests
  private MeterRegistry meterRegistry = new SimpleMeterRegistry();
  private AuthService authService;

  @BeforeEach
  void setUp() {
    authService =
        new AuthService(
            orgRepository,
            userRepository,
            refreshTokenRepository,
            passwordEncoder,
            jwtTokenProvider,
            revocationService,
            meterRegistry);
  }

  // ============================================================
  // Registration
  // ============================================================

  @Nested
  @DisplayName("registerOrganization")
  class RegisterOrganization {

    @Test
    @DisplayName("creates org and owner user when slug is available")
    void successfulRegistration() {
      var request =
          new RegisterOrganizationRequest(
              "Acme Corp", "acme-corp", "Alice Admin", "alice@acme.com", "Password1!");

      Organization savedOrg = Organization.builder()
          .id(UUID.randomUUID()).name("Acme Corp").slug("acme-corp").build();
      User savedUser = User.builder()
          .id(UUID.randomUUID()).organization(savedOrg).email("alice@acme.com")
          .passwordHash("hash").fullName("Alice Admin").role(UserRole.OWNER)
          .status(UserStatus.ACTIVE).emailVerified(false).build();

      when(orgRepository.existsBySlug("acme-corp")).thenReturn(false);
      when(orgRepository.save(any(Organization.class))).thenReturn(savedOrg);
      when(userRepository.save(any(User.class))).thenReturn(savedUser);
      when(jwtTokenProvider.generateAccessToken(any(), any(), any(), any(), any()))
          .thenReturn("access-token");
      when(refreshTokenRepository.save(any(RefreshToken.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      TokenResponse response = authService.registerOrganization(request, "127.0.0.1");

      assertThat(response.accessToken()).isEqualTo("access-token");
      assertThat(response.tokenType()).isEqualTo("Bearer");
      assertThat(response.user().email()).isEqualTo("alice@acme.com");
      assertThat(response.user().role()).isEqualTo("OWNER");

      // Verify org was saved with correct slug
      ArgumentCaptor<Organization> orgCaptor = ArgumentCaptor.forClass(Organization.class);
      verify(orgRepository).save(orgCaptor.capture());
      assertThat(orgCaptor.getValue().getSlug()).isEqualTo("acme-corp");

      // Verify user was saved with hashed password (not plaintext)
      ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
      verify(userRepository).save(userCaptor.capture());
      assertThat(userCaptor.getValue().getPasswordHash()).isNotEqualTo("Password1!");
      assertThat(userCaptor.getValue().getRole()).isEqualTo(UserRole.OWNER);
    }

    @Test
    @DisplayName("throws ConflictException when slug already taken")
    void slugConflict() {
      var request =
          new RegisterOrganizationRequest(
              "Acme Corp", "acme-corp", "Alice", "alice@acme.com", "Password1!");

      when(orgRepository.existsBySlug("acme-corp")).thenReturn(true);

      assertThatThrownBy(() -> authService.registerOrganization(request, "127.0.0.1"))
          .isInstanceOf(ConflictException.class)
          .hasMessageContaining("acme-corp");

      verify(orgRepository, never()).save(any());
      verify(userRepository, never()).save(any());
    }
  }

  // ============================================================
  // Login
  // ============================================================

  @Nested
  @DisplayName("login")
  class Login {

    private Organization org;
    private User user;

    @BeforeEach
    void setup() {
      org = Organization.builder().id(UUID.randomUUID()).name("Acme").slug("acme").build();
      String hash = passwordEncoder.encode("Password1!");
      user = User.builder()
          .id(UUID.randomUUID()).organization(org).email("alice@acme.com")
          .passwordHash(hash).fullName("Alice").role(UserRole.EDITOR)
          .status(UserStatus.ACTIVE).failedLoginCount(0).emailVerified(true).build();
    }

    @Test
    @DisplayName("returns tokens on valid credentials")
    void successfulLogin() {
      when(userRepository.findActiveByEmail("alice@acme.com", UserStatus.ACTIVE))
          .thenReturn(Optional.of(user));
      when(userRepository.save(any(User.class))).thenReturn(user);
      when(jwtTokenProvider.generateAccessToken(any(), any(), any(), any(), any()))
          .thenReturn("access-token");
      when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      TokenResponse response =
          authService.login(new LoginRequest("alice@acme.com", "Password1!"), "10.0.0.1");

      assertThat(response.accessToken()).isEqualTo("access-token");

      // Verify failed login count reset
      ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
      verify(userRepository).save(userCaptor.capture());
      assertThat(userCaptor.getValue().getFailedLoginCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("throws UNAUTHORIZED on wrong password")
    void wrongPassword() {
      when(userRepository.findActiveByEmail("alice@acme.com", UserStatus.ACTIVE))
          .thenReturn(Optional.of(user));
      when(userRepository.save(any(User.class))).thenReturn(user);

      assertThatThrownBy(
              () -> authService.login(new LoginRequest("alice@acme.com", "WrongPass!"), "10.0.0.1"))
          .isInstanceOf(VaultFlowException.class)
          .satisfies(
              e -> assertThat(((VaultFlowException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));

      // Verify failed count incremented
      ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
      verify(userRepository).save(userCaptor.capture());
      assertThat(userCaptor.getValue().getFailedLoginCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("throws TOO_MANY_REQUESTS when account is locked")
    void lockedAccount() {
      user.setLockedUntil(Instant.now().plusSeconds(300));

      when(userRepository.findActiveByEmail("alice@acme.com", UserStatus.ACTIVE))
          .thenReturn(Optional.of(user));

      assertThatThrownBy(
              () -> authService.login(new LoginRequest("alice@acme.com", "Password1!"), "10.0.0.1"))
          .isInstanceOf(VaultFlowException.class)
          .satisfies(
              e ->
                  assertThat(((VaultFlowException) e).getStatus())
                      .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("user not found returns same error as wrong password (prevents enumeration)")
    void userNotFound() {
      when(userRepository.findActiveByEmail(any(), any())).thenReturn(Optional.empty());

      assertThatThrownBy(
              () -> authService.login(new LoginRequest("nobody@acme.com", "Password1!"), "127.0.0.1"))
          .isInstanceOf(VaultFlowException.class)
          .hasMessageContaining("Invalid credentials");
    }
  }

  // ============================================================
  // Token refresh + theft detection
  // ============================================================

  @Nested
  @DisplayName("refreshToken")
  class RefreshTokenTests {

    @Test
    @DisplayName("revokes entire family when already-revoked token is reused")
    void detectsTokenTheft() {
      Organization org = Organization.builder().id(UUID.randomUUID()).slug("acme").name("Acme").build();
      User user = User.builder().id(UUID.randomUUID()).organization(org)
          .email("alice@acme.com").fullName("Alice").role(UserRole.VIEWER)
          .status(UserStatus.ACTIVE).emailVerified(true).build();

      String rawToken = "stolen-refresh-token";
      String hash = ChecksumUtil.sha256Hex(rawToken);

      RefreshToken revokedToken = RefreshToken.builder()
          .id(UUID.randomUUID()).user(user).tokenHash(hash)
          .familyId(UUID.randomUUID()).revoked(true)
          .expiresAt(Instant.now().plusSeconds(3600)).build();

      when(refreshTokenRepository.findByTokenHash(hash))
          .thenReturn(Optional.of(revokedToken));

      assertThatThrownBy(
              () -> authService.refreshToken(
                  new com.vaultflow.auth.dto.request.AuthRequests.RefreshTokenRequest(rawToken),
                  "10.0.0.1"))
          .isInstanceOf(VaultFlowException.class)
          .satisfies(
              e -> assertThat(((VaultFlowException) e).getErrorCode()).isEqualTo("TOKEN_REUSE"));

      verify(refreshTokenRepository).revokeFamily(
          eq(revokedToken.getFamilyId()), any(Instant.class), eq("TOKEN_REUSE_DETECTED"));
    }
  }
}
