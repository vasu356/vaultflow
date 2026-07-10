package com.vaultflow.processing.consumer;

import com.vaultflow.common.event.FileUploadedEvent;
import com.vaultflow.processing.service.ProcessingOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for the {@code file.uploaded} topic.
 *
 * <p>Design decisions:
 * - Manual acknowledgment (AckMode.MANUAL_IMMEDIATE): We ack only after all processors succeed.
 *   This guarantees at-least-once processing — if the pod dies mid-processing, the message is
 *   redelivered and processed again. All processors are idempotent on objectVersionId.
 *
 * - Consumer group {@code processing-service}: Kafka assigns each partition to exactly one consumer
 *   in the group. With 16 partitions, we can scale to 16 processing-service pods before hitting
 *   the partition limit.
 *
 * - Concurrency 4: Each pod runs 4 concurrent listener threads, each owning one or more
 *   partitions. Combined with 4 pods = 16 concurrent consumers. Adjust based on CPU budget.
 *
 * - DLT (Dead Letter Topic): After 3 retries, failed messages go to {@code file.uploaded.DLT}.
 *   A separate DLT consumer alerts on-call and writes to a dead_letter_log table for manual review.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FileUploadedConsumer {

  private final ProcessingOrchestrator orchestrator;

  @KafkaListener(
      topics = "file.uploaded",
      groupId = "processing-service",
      concurrency = "4",
      containerFactory = "kafkaListenerContainerFactory")
  public void consume(
      ConsumerRecord<String, FileUploadedEvent> record,
      Acknowledgment ack,
      @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
      @Header(KafkaHeaders.OFFSET) long offset) {

    FileUploadedEvent event = record.value();

    // Enrich MDC for all log statements within this processing context
    MDC.put("correlationId", event.eventId());
    MDC.put("objectVersionId", event.objectVersionId());
    MDC.put("orgId", event.orgId());

    try {
      log.info("Processing event: objectVersionId={} contentType={} size={} partition={} offset={}",
          event.objectVersionId(), event.contentType(), event.sizeBytes(), partition, offset);

      orchestrator.process(event);

      ack.acknowledge(); // Manual ack after successful processing
      log.info("Event processed successfully: objectVersionId={}", event.objectVersionId());

    } catch (Exception e) {
      log.error("Processing failed for objectVersionId={}: {}",
          event.objectVersionId(), e.getMessage(), e);
      // Do NOT acknowledge — Kafka will redeliver after retry interval.
      // Spring Kafka retry + DLT config handles exhausted retries.
      throw e;
    } finally {
      MDC.clear();
    }
  }
}
