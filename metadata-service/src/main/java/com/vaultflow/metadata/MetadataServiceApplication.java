package com.vaultflow.metadata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * VaultFlow Metadata Service
 *
 * <p>Responsibilities: - Object metadata storage and retrieval via JdbcTemplate - Tag-based search
 * and object lifecycle rule enforcement - Kafka consumer for file.processed events - Version
 * listing and metadata querying
 *
 * <p>UserDetailsServiceAutoConfiguration is excluded because this service uses JWT-based
 * authentication exclusively. RedisRepositoriesAutoConfiguration is excluded because this service
 * uses RedisTemplate directly, not Spring Data Redis repositories.
 */
@SpringBootApplication(
    scanBasePackages = {"com.vaultflow.metadata", "com.vaultflow.common"},
    exclude = {UserDetailsServiceAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
@EnableScheduling
public class MetadataServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(MetadataServiceApplication.class, args);
  }
}
