package com.vaultflow.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vaultflow.common.event.AuditEvent;
import com.vaultflow.common.event.FileProcessedEvent;
import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes audit events and file-processed events from Kafka.
 *
 * Uses Object as the ConsumerRecord value type because the listener container
 * factory deserializes to Object (supporting multiple event types on the same factory).
 * We then use Jackson to convert the LinkedHashMap (default JSON deserialization) to
 * the specific event type.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditEventConsumer {

  private final JdbcTemplate jdbc;

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .registerModule(new JavaTimeModule());

  @KafkaListener(
      topics = "audit.events",
      groupId = "notification-audit-service",
      concurrency = "2",
      containerFactory = "auditKafkaListenerContainerFactory")
  public void consumeAuditEvent(ConsumerRecord<String, Object> record, Acknowledgment ack) {
    try {
      AuditEvent event = MAPPER.convertValue(record.value(), AuditEvent.class);
      if (event == null || event.eventId() == null) {
        log.warn("Received null or malformed audit event, skipping");
        ack.acknowledge();
        return;
      }

      jdbc.update("""
          INSERT INTO audit_logs
            (id, org_id, user_id, action, resource_type, resource_id,
             ip_address, correlation_id, outcome, metadata, occurred_at)
          VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?::uuid, ?::inet, ?, ?, ?::jsonb, ?)
          ON CONFLICT (id, occurred_at) DO NOTHING
          """,
          event.eventId(),
          event.orgId(),
          event.userId(),
          event.action(),
          event.resourceType(),
          event.resourceId(),
          event.ipAddress(),
          event.correlationId(),
          event.outcome() != null ? event.outcome().name() : "SUCCESS",
          "{}",
          Timestamp.from(event.occurredAt() != null ? event.occurredAt() : Instant.now()));

      ack.acknowledge();
      log.debug("Audit event persisted: eventId={} action={}", event.eventId(), event.action());

    } catch (Exception e) {
      log.error("Failed to persist audit event from partition={} offset={}: {}",
          record.partition(), record.offset(), e.getMessage(), e);
      throw new RuntimeException("Audit event persistence failed", e);
    }
  }

  @KafkaListener(
      topics = "file.processed",
      groupId = "notification-processing-service",
      concurrency = "2",
      containerFactory = "auditKafkaListenerContainerFactory")
  public void consumeProcessedEvent(ConsumerRecord<String, Object> record, Acknowledgment ack) {
    try {
      FileProcessedEvent event = MAPPER.convertValue(record.value(), FileProcessedEvent.class);
      if (event == null) {
        ack.acknowledge();
        return;
      }
      log.info("File processing result: objectVersionId={} type={} status={}",
          event.objectVersionId(), event.processingType(), event.status());
      // Webhook delivery would go here — for now log and ack
      ack.acknowledge();
    } catch (Exception e) {
      log.error("Failed to consume processed event: {}", e.getMessage(), e);
      throw new RuntimeException("File processed event handling failed", e);
    }
  }
}
