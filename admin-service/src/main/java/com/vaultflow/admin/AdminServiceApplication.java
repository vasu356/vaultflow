package com.vaultflow.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * VaultFlow Admin Service
 *
 * <p>Responsibilities: - Organization administration and user management - Storage quota
 * enforcement - Platform-level audit log querying - Admin-scoped RBAC (requires ADMIN or
 * SUPER_ADMIN role)
 *
 * <p>UserDetailsServiceAutoConfiguration is excluded because this service uses JWT-based
 * authentication exclusively. RedisRepositoriesAutoConfiguration is excluded because this service
 * uses RedisTemplate directly, not Spring Data Redis repositories.
 */
@SpringBootApplication(
    scanBasePackages = {"com.vaultflow.admin", "com.vaultflow.common"},
    exclude = {UserDetailsServiceAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
public class AdminServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AdminServiceApplication.class, args);
  }
}
