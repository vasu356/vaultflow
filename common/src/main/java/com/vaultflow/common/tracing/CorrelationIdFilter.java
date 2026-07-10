package com.vaultflow.common.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extracts or generates a correlation ID for every incoming HTTP request and binds it to:
 *
 * <ul>
 *   <li>SLF4J MDC for structured log output
 *   <li>Response header for client-side tracing
 * </ul>
 *
 * <p>Why correlation IDs? Without them, tracing a single user action across upload-service →
 * processing-service → notification-service requires joining logs by timestamp and object ID —
 * error-prone and slow. Correlation IDs make cross-service traces instant.
 *
 * <p>Priority: Order(1) ensures this runs before all other filters including security filters, so
 * even authentication failures are tagged with correlation IDs.
 */
@Component
@Order(1)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
  public static final String MDC_CORRELATION_ID = "correlationId";
  public static final String MDC_USER_ID = "userId";
  public static final String MDC_ORG_ID = "orgId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String correlationId = request.getHeader(CORRELATION_ID_HEADER);
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = UUID.randomUUID().toString();
    }

    MDC.put(MDC_CORRELATION_ID, correlationId);
    response.setHeader(CORRELATION_ID_HEADER, correlationId);

    try {
      chain.doFilter(request, response);
    } finally {
      // Critical: MDC is ThreadLocal. Must clear after request to prevent leakage
      // to the next request handled by this thread (especially relevant for thread pools).
      MDC.remove(MDC_CORRELATION_ID);
      MDC.remove(MDC_USER_ID);
      MDC.remove(MDC_ORG_ID);
    }
  }
}
