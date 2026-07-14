package com.vaultflow.upload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * VaultFlow Upload Service
 *
 * <p>Responsibilities: - Multipart upload orchestration - Object storage via MinIO - Pre-signed
 * upload URL generation - File event publishing to Kafka (file.uploaded topic)
 *
 * <p>UserDetailsServiceAutoConfiguration is excluded because this service uses JWT-based
 * authentication exclusively. RedisRepositoriesAutoConfiguration is excluded because this service
 * uses RedisTemplate directly, not Spring Data Redis repositories.
 */
@SpringBootApplication(
    scanBasePackages = {"com.vaultflow.upload", "com.vaultflow.common"},
    exclude = {UserDetailsServiceAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
@EnableScheduling
public class UploadServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(UploadServiceApplication.class, args);
  }
}
