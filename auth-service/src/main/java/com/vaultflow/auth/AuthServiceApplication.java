package com.vaultflow.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * VaultFlow Auth Service
 *
 * <p>Responsibilities: - User registration and organization provisioning - Authentication
 * (login/logout/token refresh) - JWT issuance (RS256 asymmetric signing) - RBAC enforcement entry
 * point - Refresh token rotation with theft detection
 *
 * <p>Virtual threads (Java 21) are enabled via spring.threads.virtual.enabled=true in
 * application.yml. This allows the Tomcat thread pool to handle I/O-bound auth operations (DB +
 * Redis) without blocking OS threads, dramatically increasing concurrent request throughput with no
 * code changes.
 *
 * <p>UserDetailsServiceAutoConfiguration is excluded because this service uses JWT-based
 * authentication exclusively — Spring Security's in-memory user details service is never needed and
 * would otherwise log a generated password on every startup.
 *
 * <p>RedisRepositoriesAutoConfiguration is excluded because this service uses StringRedisTemplate
 * directly (TokenRevocationService), not Spring Data Redis repositories.
 */
@SpringBootApplication(
    scanBasePackages = {"com.vaultflow.auth", "com.vaultflow.common"},
    exclude = {UserDetailsServiceAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
@EnableScheduling
public class AuthServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(AuthServiceApplication.class, args);
  }
}
