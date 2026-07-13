package com.vaultflow.processing.service;

import com.vaultflow.common.event.FileProcessedEvent;
import com.vaultflow.common.event.FileUploadedEvent;
import com.vaultflow.processing.processor.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Coordinates the processing pipeline for newly uploaded objects.
 *
 * <p>Processors run in parallel using a bounded virtual-thread executor. Each processor is
 * independent — image thumbnail failure does not block virus scan. Results are collected and
 * published to {@code file.processed} topic.
 *
 * <p>Why parallel? Processing a 50 MB video: virus scan (2s) + thumbnail (1s) run concurrently = 2s
 * total. Sequential would be 3s. At scale, this matters significantly.
 *
 * <p>Why bounded executor? Prevents unbounded memory growth if Kafka delivers faster than
 * processors complete. Backpressure flows naturally — when the pool is saturated, consumer.poll()
 * blocks, which reduces Kafka fetch pressure automatically.
 */
@Service
@Slf4j
public class ProcessingOrchestrator {

  private static final String TOPIC_FILE_PROCESSED = "file.processed";

  private final ImageThumbnailProcessor imageThumbnailProcessor;
  private final VideoThumbnailProcessor videoThumbnailProcessor;
  private final PdfPreviewProcessor pdfPreviewProcessor;
  private final VirusScanProcessor virusScanProcessor;
  private final MetadataExtractionProcessor metadataExtractionProcessor;
  private final ProcessingResultPersistenceService persistenceService;
  private final KafkaTemplate<String, Object> kafkaTemplate;
  private final MeterRegistry meterRegistry;
  private final ExecutorService processingExecutor;

  public ProcessingOrchestrator(
      ImageThumbnailProcessor imageThumbnailProcessor,
      VideoThumbnailProcessor videoThumbnailProcessor,
      PdfPreviewProcessor pdfPreviewProcessor,
      VirusScanProcessor virusScanProcessor,
      MetadataExtractionProcessor metadataExtractionProcessor,
      ProcessingResultPersistenceService persistenceService,
      KafkaTemplate<String, Object> kafkaTemplate,
      MeterRegistry meterRegistry,
      @Value("${vaultflow.processing.pool-size:8}") int poolSize) {

    this.imageThumbnailProcessor = imageThumbnailProcessor;
    this.videoThumbnailProcessor = videoThumbnailProcessor;
    this.pdfPreviewProcessor = pdfPreviewProcessor;
    this.virusScanProcessor = virusScanProcessor;
    this.metadataExtractionProcessor = metadataExtractionProcessor;
    this.persistenceService = persistenceService;
    this.kafkaTemplate = kafkaTemplate;
    this.meterRegistry = meterRegistry;
    // Virtual threads: lightweight, no thread-per-core limitation
    this.processingExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  public void process(FileUploadedEvent event) {
    Timer.Sample timer = Timer.start(meterRegistry);
    List<CompletableFuture<FileProcessedEvent>> futures = new ArrayList<>();

    if (event.requiresVirusScan()) {
      futures.add(runAsync(() -> virusScanProcessor.process(event)));
    }

    if (event.requiresImageProcessing()) {
      futures.add(runAsync(() -> imageThumbnailProcessor.process(event)));
    }

    if (event.requiresVideoProcessing()) {
      futures.add(runAsync(() -> videoThumbnailProcessor.process(event)));
    }

    if (event.requiresDocumentProcessing()) {
      futures.add(runAsync(() -> pdfPreviewProcessor.process(event)));
    }

    // Always extract metadata
    futures.add(runAsync(() -> metadataExtractionProcessor.process(event)));

    // Wait for all processors and collect results
    List<FileProcessedEvent> results =
        futures.stream()
            .map(CompletableFuture::join) // join (not get) — propagates unchecked exceptions
            .toList();

    // Persist results to DB (update processing_status, thumbnail_key, virus_scan_status)
    persistenceService.persistResults(event.objectVersionId(), results);

    // Publish each result event for downstream consumers (notification-service)
    results.forEach(result -> kafkaTemplate.send(TOPIC_FILE_PROCESSED, event.objectId(), result));

    timer.stop(
        meterRegistry.timer(
            "processing.pipeline.duration",
            "contentType",
            event.contentType() != null ? event.contentType().split("/")[0] : "unknown"));

    meterRegistry.counter("processing.pipeline.completed").increment();
  }

  private CompletableFuture<FileProcessedEvent> runAsync(
      java.util.concurrent.Callable<FileProcessedEvent> processor) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return processor.call();
          } catch (Exception e) {
            // Individual processor failure is captured, not propagated, to allow other processors
            // to continue
            log.error("Processor failed: {}", e.getMessage(), e);
            throw new RuntimeException(e);
          }
        },
        processingExecutor);
  }
}
