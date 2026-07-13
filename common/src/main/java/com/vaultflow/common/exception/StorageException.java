package com.vaultflow.common.exception;

import org.springframework.http.HttpStatus;

public class StorageException extends VaultFlowException {

  public StorageException(String message, Throwable cause) {
    super(message, HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", cause);
  }

  public StorageException(String message) {
    super(message, HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR");
  }
}
