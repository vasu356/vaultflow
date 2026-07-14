package com.vaultflow.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * VaultFlow Notification Service
 *
 * <p>Responsibilities: - Kafka consumer for audit.events and file.processed topics - Audit log
 * persistence (JdbcTemplate → audit_logs table) - Email notification dispatch on file processing
 * completion - Webhook delivery for configurable event subscriptions
 *
 * <p>This service is a pure Kafka consumer with no HTTP controllers. Actuator endpoints are exposed
 * for health/metrics only. UserDetailsServiceAutoConfiguration is excluded because JWT tokens are
 * validated at the API gateway layer; this service trusts pre-authenticated Kafka messages.
 */
@SpringBootApplication(
    scanBasePackages = {"com.vaultflow.notification", "com.vaultflow.common"},
    exclude = {UserDetailsServiceAutoConfiguration.class})
public class NotificationServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(NotificationServiceApplication.class, args);
  }
}
