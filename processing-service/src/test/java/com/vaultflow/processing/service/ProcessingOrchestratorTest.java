package com.vaultflow.processing.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vaultflow.common.event.FileProcessedEvent;
import com.vaultflow.common.event.FileUploadedEvent;
import com.vaultflow.processing.processor.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProcessingOrchestrator")
class ProcessingOrchestratorTest {

  @Mock ImageThumbnailProcessor imageThumbnailProcessor;
  @Mock VideoThumbnailProcessor videoThumbnailProcessor;
  @Mock PdfPreviewProcessor pdfPreviewProcessor;
  @Mock VirusScanProcessor virusScanProcessor;
  @Mock MetadataExtractionProcessor metadataExtractionProcessor;
  @Mock ProcessingResultPersistenceService persistenceService;
  @Mock KafkaTemplate<String, Object> kafkaTemplate;

  ProcessingOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    orchestrator = new ProcessingOrchestrator(
        imageThumbnailProcessor, videoThumbnailProcessor, pdfPreviewProcessor,
        virusScanProcessor, metadataExtractionProcessor,
        persistenceService, kafkaTemplate, new SimpleMeterRegistry(), 4);
  }

  private FileUploadedEvent imageEvent() {
    return new FileUploadedEvent(
        UUID.randomUUID().toString(), 1,
        UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), "images/photo.jpg",
        "abc/def/abc123", "image/jpeg",
        1_000_000L, "abc123",
        true, false, false, true, // image, not video, not doc, virus scan
        Instant.now());
  }

  private FileProcessedEvent successEvent(FileProcessedEvent.ProcessingType type) {
    return FileProcessedEvent.success(
        UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        type, Map.of("status", "ok"));
  }

  @Test
  @DisplayName("runs image processor and virus scan in parallel for image uploads")
  void processImageWithVirusScan() {
    FileUploadedEvent event = imageEvent();

    when(virusScanProcessor.process(event))
        .thenReturn(successEvent(FileProcessedEvent.ProcessingType.VIRUS_SCAN));
    when(imageThumbnailProcessor.process(event))
        .thenReturn(successEvent(FileProcessedEvent.ProcessingType.IMAGE_THUMBNAIL));
    when(metadataExtractionProcessor.process(event))
        .thenReturn(successEvent(FileProcessedEvent.ProcessingType.METADATA_EXTRACTION));
    when(kafkaTemplate.send(any(), any(), any())).thenReturn(null);

    assertThatNoException().isThrownBy(() -> orchestrator.process(event));

    verify(virusScanProcessor).process(event);
    verify(imageThumbnailProcessor).process(event);
    verify(metadataExtractionProcessor).process(event);
    verify(videoThumbnailProcessor, never()).process(any());
    verify(pdfPreviewProcessor, never()).process(any());
    verify(persistenceService).persistResults(eq(event.objectVersionId()), anyList());
  }

  @Test
  @DisplayName("all processors run for video uploads")
  void processVideoRunsVideoAndVirusScan() {
    FileUploadedEvent event = new FileUploadedEvent(
        UUID.randomUUID().toString(), 1,
        UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), "videos/clip.mp4",
        "abc/def/abc123", "video/mp4",
        50_000_000L, "abc123",
        false, true, false, true,
        Instant.now());

    when(virusScanProcessor.process(event))
        .thenReturn(successEvent(FileProcessedEvent.ProcessingType.VIRUS_SCAN));
    when(videoThumbnailProcessor.process(event))
        .thenReturn(successEvent(FileProcessedEvent.ProcessingType.VIDEO_THUMBNAIL));
    when(metadataExtractionProcessor.process(event))
        .thenReturn(successEvent(FileProcessedEvent.ProcessingType.METADATA_EXTRACTION));
    when(kafkaTemplate.send(any(), any(), any())).thenReturn(null);

    orchestrator.process(event);

    verify(videoThumbnailProcessor).process(event);
    verify(virusScanProcessor).process(event);
    verify(imageThumbnailProcessor, never()).process(any());
    verify(pdfPreviewProcessor, never()).process(any());
  }

  @Test
  @DisplayName("processors run concurrently — verifies parallel execution timing")
  void processorsRunInParallel() throws Exception {
    FileUploadedEvent event = imageEvent();
    AtomicInteger concurrencyPeak = new AtomicInteger(0);
    AtomicInteger activeCount = new AtomicInteger(0);

    // Each processor sleeps 100ms — sequential would take 300ms, parallel ~100ms
    when(imageThumbnailProcessor.process(any())).thenAnswer(inv -> {
      int current = activeCount.incrementAndGet();
      concurrencyPeak.updateAndGet(peak -> Math.max(peak, current));
      Thread.sleep(100);
      activeCount.decrementAndGet();
      return successEvent(FileProcessedEvent.ProcessingType.IMAGE_THUMBNAIL);
    });
    when(virusScanProcessor.process(any())).thenAnswer(inv -> {
      int current = activeCount.incrementAndGet();
      concurrencyPeak.updateAndGet(peak -> Math.max(peak, current));
      Thread.sleep(100);
      activeCount.decrementAndGet();
      return successEvent(FileProcessedEvent.ProcessingType.VIRUS_SCAN);
    });
    when(metadataExtractionProcessor.process(any())).thenAnswer(inv -> {
      int current = activeCount.incrementAndGet();
      concurrencyPeak.updateAndGet(peak -> Math.max(peak, current));
      Thread.sleep(100);
      activeCount.decrementAndGet();
      return successEvent(FileProcessedEvent.ProcessingType.METADATA_EXTRACTION);
    });
    when(kafkaTemplate.send(any(), any(), any())).thenReturn(null);

    long start = System.currentTimeMillis();
    orchestrator.process(event);
    long elapsed = System.currentTimeMillis() - start;

    // Should complete in ~100-250ms (parallel), not 300ms+ (sequential)
    assertThat(elapsed).isLessThan(300L);
    // At least 2 processors were running simultaneously
    assertThat(concurrencyPeak.get()).isGreaterThanOrEqualTo(2);
  }

  @Test
  @DisplayName("publishes one FileProcessedEvent per processor result")
  void publishesProcessedEventsPerResult() {
    FileUploadedEvent event = imageEvent();

    when(imageThumbnailProcessor.process(event))
        .thenReturn(successEvent(FileProcessedEvent.ProcessingType.IMAGE_THUMBNAIL));
    when(virusScanProcessor.process(event))
        .thenReturn(successEvent(FileProcessedEvent.ProcessingType.VIRUS_SCAN));
    when(metadataExtractionProcessor.process(event))
        .thenReturn(successEvent(FileProcessedEvent.ProcessingType.METADATA_EXTRACTION));
    when(kafkaTemplate.send(any(), any(), any())).thenReturn(null);

    orchestrator.process(event);

    // 3 processors = 3 published events
    verify(kafkaTemplate, times(3)).send(eq("file.processed"), any(), any());
  }
}
