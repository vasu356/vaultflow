package com.vaultflow.processing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.vaultflow.processing", "com.vaultflow.common"})
public class ProcessingServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(ProcessingServiceApplication.class, args);
  }
}
