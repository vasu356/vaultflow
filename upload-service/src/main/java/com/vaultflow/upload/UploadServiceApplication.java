package com.vaultflow.upload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.vaultflow.upload", "com.vaultflow.common"})
@EnableScheduling
public class UploadServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(UploadServiceApplication.class, args);
  }
}
