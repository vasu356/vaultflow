package com.vaultflow.common.exception;

import com.vaultflow.common.dto.ApiErrorResponse;
import com.vaultflow.common.tracing.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler — auto-configured for all web services via
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports.
 *
 * <p>{@code @ConditionalOnWebApplication} ensures this bean is only created in servlet-based web
 * apps (not in processing-service or notification-service which are pure Kafka consumers with no
 * HTTP controllers, but happen to have spring-web on classpath for actuator).
 */
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(VaultFlowException.class)
  public ResponseEntity<ApiErrorResponse> handleVaultFlowException(
      VaultFlowException ex, HttpServletRequest request) {
    log.warn(
        "Domain exception: errorCode={} message={} path={}",
        ex.getErrorCode(),
        ex.getMessage(),
        request.getRequestURI());
    return ResponseEntity.status(ex.getStatus())
        .body(
            ApiErrorResponse.of(
                ex.getErrorCode(),
                ex.getMessage(),
                ex.getStatus().value(),
                request.getRequestURI(),
                MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID)));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorResponse> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<ApiErrorResponse.FieldError> fieldErrors =
        ex.getBindingResult().getAllErrors().stream()
            .map(
                error -> {
                  if (error instanceof FieldError fe) {
                    return new ApiErrorResponse.FieldError(
                        fe.getField(), fe.getDefaultMessage(), fe.getRejectedValue());
                  }
                  return new ApiErrorResponse.FieldError(
                      error.getObjectName(), error.getDefaultMessage(), null);
                })
            .toList();

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            ApiErrorResponse.withFieldErrors(
                "VALIDATION_FAILED",
                "Request validation failed",
                HttpStatus.BAD_REQUEST.value(),
                request.getRequestURI(),
                MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID),
                fieldErrors));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
    log.error("Unhandled exception at path={}", request.getRequestURI(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            ApiErrorResponse.of(
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                request.getRequestURI(),
                MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID)));
  }
}
