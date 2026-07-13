package com.vaultflow.auth.domain.enums;

/**
 * RBAC roles in descending privilege order.
 *
 * <p>OWNER: Full control including deleting the organization, managing billing, promoting to ADMIN.
 * Exactly one OWNER per organization (created at org registration). Cannot be removed without
 * transferring ownership.
 *
 * <p>ADMIN: Full control over all org resources. Can manage users up to EDITOR level. Cannot
 * promote to OWNER or demote the current OWNER.
 *
 * <p>EDITOR: Create, read, update, and delete objects in all buckets. Cannot manage users, bucket
 * policies, or org settings.
 *
 * <p>VIEWER: Read-only access to objects they've been granted access to. Cannot create or delete
 * objects.
 */
public enum UserRole {
  OWNER(40),
  ADMIN(30),
  EDITOR(20),
  VIEWER(10);

  private final int level;

  UserRole(int level) {
    this.level = level;
  }

  public int getLevel() {
    return level;
  }

  public boolean hasAtLeast(UserRole required) {
    return this.level >= required.level;
  }
}
