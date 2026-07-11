package com.vaultflow.processing.processor;

import static org.assertj.core.api.Assertions.*;

import com.vaultflow.common.event.FileProcessedEvent;
import com.vaultflow.common.event.FileUploadedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("VirusScanProcessor")
class VirusScanProcessorTest {

  @TempDir Path tempDir;

  VirusScanProcessor processor;

  @BeforeEach
  void setUp() throws Exception {
    processor = new VirusScanProcessor(new SimpleMeterRegistry());
    // Set storage base dir to temp directory via reflection
    var field = VirusScanProcessor.class.getDeclaredField("storageBaseDir");
    field.setAccessible(true);
    field.set(processor, tempDir.toString());
    var maxField = VirusScanProcessor.class.getDeclaredField("maxFileSizeBytes");
    maxField.setAccessible(true);
    maxField.set(processor, 104857600L);
  }

  private FileUploadedEvent makeEvent(String storageKey) {
    return new FileUploadedEvent(
        UUID.randomUUID().toString(),
        1,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        "test.txt",
        storageKey,
        "text/plain",
        100L,
        "abc",
        false,
        false,
        false,
        true,
        Instant.now());
  }

  @Test
  @DisplayName("returns CLEAN for a normal file")
  void cleanFile() throws IOException {
    String storageKey = "abc/def/cleanfile";
    Path file = tempDir.resolve("abc").resolve("def");
    Files.createDirectories(file);
    Files.writeString(file.resolve("cleanfile"), "Hello, this is a normal text file.");

    FileUploadedEvent event = makeEvent(storageKey);
    FileProcessedEvent result = processor.process(event);

    assertThat(result.status()).isEqualTo(FileProcessedEvent.ProcessingStatus.SUCCESS);
    assertThat(result.resultMetadata().get("status")).isEqualTo("CLEAN");
  }

  @Test
  @DisplayName("detects EICAR test string as INFECTED")
  void detectedEicar() throws IOException {
    String eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
    String storageKey = "abc/def/eicarfile";
    Path dir = tempDir.resolve("abc").resolve("def");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("eicarfile"), eicar);

    FileUploadedEvent event = makeEvent(storageKey);
    FileProcessedEvent result = processor.process(event);

    assertThat(result.status()).isEqualTo(FileProcessedEvent.ProcessingStatus.SUCCESS);
    assertThat(result.resultMetadata().get("status")).isEqualTo("INFECTED");
    assertThat(result.resultMetadata().get("threat")).isEqualTo("EICAR.Test.File");
  }

  @Test
  @DisplayName("returns FAILED when source file does not exist")
  void missingFile() {
    FileUploadedEvent event = makeEvent("nonexistent/path/file");
    FileProcessedEvent result = processor.process(event);
    assertThat(result.status()).isEqualTo(FileProcessedEvent.ProcessingStatus.FAILED);
  }

  @Test
  @DisplayName("returns SKIPPED when file exceeds max scan size")
  void oversizedFile() throws Exception {
    // Set max to 10 bytes
    var maxField = VirusScanProcessor.class.getDeclaredField("maxFileSizeBytes");
    maxField.setAccessible(true);
    maxField.set(processor, 10L);

    String storageKey = "abc/def/bigfile";
    Path dir = tempDir.resolve("abc").resolve("def");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("bigfile"), "This file is larger than 10 bytes for testing.");

    FileUploadedEvent event = makeEvent(storageKey);
    FileProcessedEvent result = processor.process(event);

    assertThat(result.status()).isEqualTo(FileProcessedEvent.ProcessingStatus.SUCCESS);
    assertThat(result.resultMetadata().get("status")).isEqualTo("SKIPPED");
  }
}
