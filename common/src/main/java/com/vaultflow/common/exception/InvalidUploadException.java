package com.vaultflow.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidUploadException extends VaultFlowException {
  public InvalidUploadException(String message) {
    super(message, HttpStatus.BAD_REQUEST, "INVALID_UPLOAD");
  }
}
