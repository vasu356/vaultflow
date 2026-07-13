package com.vaultflow.common.event;

import java.time.Instant;
import java.util.Map;

/**
 * Published to Kafka topic {@code audit.events} for every significant user or system action.
 *
 * <p>Design: We use Kafka for audit events (not direct DB writes) because: 1. Audit writes must not
 * block or slow down the primary request path 2. Audit events may need to fan out to SIEM,
 * compliance systems, and multiple DB partitions 3. Kafka provides guaranteed ordering within a
 * partition (keyed by orgId) 4. Events are retained for 30 days in Kafka for replay/recovery
 */
public record AuditEvent(
    String eventId,
    String orgId,
    String userId,
    String action,
    String resourceType,
    String resourceId,
    String ipAddress,
    String userAgent,
    String correlationId,
    Map<String, Object> before,
    Map<String, Object> after,
    AuditOutcome outcome,
    String failureReason,
    Instant occurredAt) {

  public enum AuditOutcome {
    SUCCESS,
    FAILURE,
    PARTIAL
  }

  public enum Action {
    // Auth
    USER_REGISTERED,
    USER_LOGIN,
    USER_LOGOUT,
    TOKEN_REFRESHED,
    PASSWORD_CHANGED,

    // Bucket operations
    BUCKET_CREATED,
    BUCKET_UPDATED,
    BUCKET_DELETED,

    // Object operations
    OBJECT_UPLOADED,
    OBJECT_DOWNLOADED,
    OBJECT_DELETED,
    OBJECT_RESTORED,
    OBJECT_PERMANENTLY_DELETED,
    OBJECT_MOVED,
    OBJECT_COPIED,

    // Access control
    PERMISSION_GRANTED,
    PERMISSION_REVOKED,
    SIGNED_URL_CREATED,
    SIGNED_URL_ACCESSED,

    // Admin
    QUOTA_UPDATED,
    USER_SUSPENDED,
    USER_ACTIVATED
  }
}
