package com.vaultflow.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Canonical error response envelope. Every service returns this shape on error, enabling clients to
 * handle errors uniformly regardless of which downstream service generated them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
    String errorCode,
    String message,
    int status,
    String path,
    String correlationId,
    Instant timestamp,
    List<FieldError> fieldErrors,
    Map<String, Object> metadata) {

  public record FieldError(String field, String message, Object rejectedValue) {}

  /** Factory for simple errors. */
  public static ApiErrorResponse of(
      String errorCode, String message, int status, String path, String correlationId) {
    return new ApiErrorResponse(
        errorCode, message, status, path, correlationId, Instant.now(), null, null);
  }

  /** Factory for validation errors with field-level detail. */
  public static ApiErrorResponse withFieldErrors(
      String errorCode,
      String message,
      int status,
      String path,
      String correlationId,
      List<FieldError> fieldErrors) {
    return new ApiErrorResponse(
        errorCode, message, status, path, correlationId, Instant.now(), fieldErrors, null);
  }
}
