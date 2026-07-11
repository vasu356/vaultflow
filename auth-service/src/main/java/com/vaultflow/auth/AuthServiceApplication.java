package com.vaultflow.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
 */
@SpringBootApplication(scanBasePackages = {"com.vaultflow.auth", "com.vaultflow.common"})
@EnableScheduling
public class AuthServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(AuthServiceApplication.class, args);
  }
}
