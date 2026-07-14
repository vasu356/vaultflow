package com.vaultflow.download;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * VaultFlow Download Service
 *
 * <p>Responsibilities: - Pre-signed URL generation and validation - Object streaming from MinIO -
 * Access control enforcement (bucket ACL + IAM policies) - Signed URL token lifecycle management
 *
 * <p>UserDetailsServiceAutoConfiguration is excluded because this service uses JWT-based
 * authentication exclusively. RedisRepositoriesAutoConfiguration is excluded because this service
 * uses RedisTemplate directly, not Spring Data Redis repositories.
 */
@SpringBootApplication(
    scanBasePackages = {"com.vaultflow.download", "com.vaultflow.common"},
    exclude = {UserDetailsServiceAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
public class DownloadServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(DownloadServiceApplication.class, args);
  }
}
