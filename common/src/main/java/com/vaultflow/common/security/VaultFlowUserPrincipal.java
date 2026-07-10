package com.vaultflow.common.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security principal populated from JWT claims on every authenticated request.
 *
 * <p>This is the central identity object — injected into controllers via {@code @AuthenticationPrincipal}.
 * Carries all context needed for RBAC checks without requiring a DB lookup per request.
 *
 * <p>Role hierarchy enforced at service layer:
 * OWNER > ADMIN > EDITOR > VIEWER
 */
public record VaultFlowUserPrincipal(
    String userId,
    String email,
    String orgId,
    String role,
    List<String> scopes,
    String correlationId)
    implements UserDetails {

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + role));
  }

  @Override
  public String getPassword() {
    return null; // Not stored — we validate via JWT, not password
  }

  @Override
  public String getUsername() {
    return email;
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return true;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return true;
  }

  public boolean isOwner() {
    return "OWNER".equals(role);
  }

  public boolean isAdmin() {
    return "ADMIN".equals(role) || isOwner();
  }

  public boolean isEditor() {
    return "EDITOR".equals(role) || isAdmin();
  }

  public boolean canWrite() {
    return scopes != null && (scopes.contains("write") || scopes.contains("admin"));
  }

  public boolean canDelete() {
    return scopes != null && (scopes.contains("delete") || scopes.contains("admin"));
  }

  public boolean canAdmin() {
    return scopes != null && scopes.contains("admin");
  }
}
