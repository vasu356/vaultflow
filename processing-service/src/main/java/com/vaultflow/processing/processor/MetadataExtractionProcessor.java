package com.vaultflow.processing.processor;

import com.vaultflow.common.event.FileProcessedEvent;
import com.vaultflow.common.event.FileUploadedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Extracts file metadata (size, creation time, media-specific properties). Results are stored in
 * object_versions.metadata JSONB column.
 *
 * <p>Production extension: Integrate Apache Tika for deep metadata extraction (EXIF from images,
 * ID3 tags from audio, document properties from Office files).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MetadataExtractionProcessor {

  private final MeterRegistry meterRegistry;

  @Value("${vaultflow.storage.base-dir:/data/vaultflow/objects}")
  private String storageBaseDir;

  public FileProcessedEvent process(FileUploadedEvent event) {
    try {
      Path sourcePath = Paths.get(storageBaseDir, event.storageKey());

      Map<String, String> metadata = new HashMap<>();
      metadata.put("originalSize", String.valueOf(event.sizeBytes()));
      metadata.put("contentType", event.contentType());
      metadata.put("checksumSha256", event.checksumSha256());

      if (Files.exists(sourcePath)) {
        BasicFileAttributes attrs = Files.readAttributes(sourcePath, BasicFileAttributes.class);
        metadata.put("creationTime", attrs.creationTime().toString());
        metadata.put("lastModifiedTime", attrs.lastModifiedTime().toString());
      }

      // Type-specific metadata hints
      if (event.requiresImageProcessing()) {
        metadata.put("mediaType", "image");
      } else if (event.requiresVideoProcessing()) {
        metadata.put("mediaType", "video");
      } else if (event.requiresDocumentProcessing()) {
        metadata.put("mediaType", "document");
      }

      meterRegistry.counter("processing.metadata.extracted").increment();

      return FileProcessedEvent.success(
          event.objectVersionId(),
          event.objectId(),
          event.bucketId(),
          event.orgId(),
          FileProcessedEvent.ProcessingType.METADATA_EXTRACTION,
          metadata);

    } catch (IOException e) {
      log.warn("Metadata extraction failed: {}", e.getMessage());
      return FileProcessedEvent.failed(
          event.objectVersionId(),
          event.objectId(),
          event.bucketId(),
          event.orgId(),
          FileProcessedEvent.ProcessingType.METADATA_EXTRACTION,
          e.getMessage());
    }
  }
}
