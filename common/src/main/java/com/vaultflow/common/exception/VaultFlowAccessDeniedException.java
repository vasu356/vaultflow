package com.vaultflow.common.exception;

import org.springframework.http.HttpStatus;

public class VaultFlowAccessDeniedException extends VaultFlowException {
  public VaultFlowAccessDeniedException(String message) {
    super(message, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
  }
}
