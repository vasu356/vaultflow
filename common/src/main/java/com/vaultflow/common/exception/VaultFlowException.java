package com.vaultflow.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base exception for all VaultFlow domain exceptions.
 *
 * <p>Every service-level exception extends this class to provide consistent HTTP status mapping and
 * machine-readable error codes for API error responses.
 */
public class VaultFlowException extends RuntimeException {

  private final HttpStatus status;
  private final String errorCode;

  public VaultFlowException(String message, HttpStatus status, String errorCode) {
    super(message);
    this.status = status;
    this.errorCode = errorCode;
  }

  public VaultFlowException(
      String message, HttpStatus status, String errorCode, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.errorCode = errorCode;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getErrorCode() {
    return errorCode;
  }
}
