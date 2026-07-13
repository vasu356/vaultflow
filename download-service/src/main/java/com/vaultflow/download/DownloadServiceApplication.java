package com.vaultflow.download;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.vaultflow.download", "com.vaultflow.common"})
public class DownloadServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(DownloadServiceApplication.class, args);
  }
}
