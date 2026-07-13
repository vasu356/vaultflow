package com.vaultflow.common.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends VaultFlowException {
  public ConflictException(String message) {
    super(message, HttpStatus.CONFLICT, "CONFLICT");
  }
}
