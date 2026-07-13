package com.vaultflow.upload.kafka;

import com.vaultflow.common.event.AuditEvent;
import com.vaultflow.common.event.FileUploadedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes upload lifecycle events to Kafka.
 *
 * <p>Ordering guarantee: We use objectId as the Kafka message key. This guarantees that all events
 * for the same object land on the same partition, preserving ordering for downstream consumers
 * (e.g. processing service sees UPLOADED before DELETED).
 *
 * <p>Reliability: KafkaTemplate with acks=all + enable.idempotence=true. On transient broker
 * failure, the producer retries up to 3 times with exponential backoff. If the broker is
 * unreachable after retries, the CompletableFuture exceptionally callback logs the failure — the
 * upload itself has already succeeded (object is stored). A reconciliation job can replay missed
 * events from DB state.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UploadEventPublisher {

  private static final String TOPIC_FILE_UPLOADED = "file.uploaded";
  private static final String TOPIC_AUDIT_EVENTS = "audit.events";

  private final KafkaTemplate<String, Object> kafkaTemplate;
  private final MeterRegistry meterRegistry;

  public void publishFileUploaded(FileUploadedEvent event) {
    kafkaTemplate
        .send(TOPIC_FILE_UPLOADED, event.objectId(), event)
        .whenComplete(
            (result, ex) -> {
              if (ex != null) {
                log.error(
                    "Failed to publish FileUploadedEvent: objectId={} error={}",
                    event.objectId(),
                    ex.getMessage());
                meterRegistry
                    .counter("kafka.publish.failures", "topic", TOPIC_FILE_UPLOADED)
                    .increment();
              } else {
                log.debug(
                    "Published FileUploadedEvent: objectId={} partition={} offset={}",
                    event.objectId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
                meterRegistry
                    .counter("kafka.publish.successes", "topic", TOPIC_FILE_UPLOADED)
                    .increment();
              }
            });
  }

  public void publishAuditEvent(
      String orgId,
      String userId,
      String action,
      String resourceType,
      String resourceId,
      String ip,
      AuditEvent.AuditOutcome outcome) {

    AuditEvent event =
        new AuditEvent(
            UUID.randomUUID().toString(),
            orgId,
            userId,
            action,
            resourceType,
            resourceId,
            ip,
            null,
            null,
            null,
            null,
            outcome,
            null,
            Instant.now());

    // Partition by orgId for ordered audit log per organization
    kafkaTemplate
        .send(TOPIC_AUDIT_EVENTS, orgId, event)
        .whenComplete(
            (result, ex) -> {
              if (ex != null) {
                log.error(
                    "Failed to publish AuditEvent: action={} error={}", action, ex.getMessage());
              }
            });
  }
}
