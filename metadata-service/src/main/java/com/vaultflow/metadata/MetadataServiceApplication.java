package com.vaultflow.metadata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.vaultflow.metadata", "com.vaultflow.common"})
@EnableScheduling
public class MetadataServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(MetadataServiceApplication.class, args);
  }
}
