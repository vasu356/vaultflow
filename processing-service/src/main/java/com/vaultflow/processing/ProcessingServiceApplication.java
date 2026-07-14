package com.vaultflow.processing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * VaultFlow Processing Service
 *
 * <p>Responsibilities: - Kafka consumer for file.uploaded events - Virus scanning orchestration
 * (ClamAV) - Image/video thumbnail generation - File format validation and metadata extraction -
 * Publishing file.processed events on completion
 *
 * <p>This service is a pure Kafka consumer with no HTTP controllers. Actuator endpoints are exposed
 * for health/metrics only. UserDetailsServiceAutoConfiguration is excluded because JWT tokens are
 * validated at the API gateway layer; this service trusts pre-authenticated Kafka messages.
 */
@SpringBootApplication(
    scanBasePackages = {"com.vaultflow.processing", "com.vaultflow.common"},
    exclude = {UserDetailsServiceAutoConfiguration.class})
public class ProcessingServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(ProcessingServiceApplication.class, args);
  }
}
