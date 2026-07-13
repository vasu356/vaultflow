package com.vaultflow.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends VaultFlowException {
  public ResourceNotFoundException(String resource, String identifier) {
    super(
        String.format("%s not found: %s", resource, identifier),
        HttpStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND");
  }
}
