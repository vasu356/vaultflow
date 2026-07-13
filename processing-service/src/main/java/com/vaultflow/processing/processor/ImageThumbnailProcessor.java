package com.vaultflow.processing.processor;

import com.vaultflow.common.event.FileProcessedEvent;
import com.vaultflow.common.event.FileUploadedEvent;
import com.vaultflow.common.util.ChecksumUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Generates image thumbnails using Thumbnailator.
 *
 * <p>Thumbnailator is chosen over ImageIO/BufferedImage because: - Handles EXIF rotation
 * automatically - Memory-efficient streaming resizing (no full decode into BufferedImage) -
 * Supports JPEG, PNG, GIF, BMP, WebP - Maintains aspect ratio with quality control
 *
 * <p>Output: 300x300 JPEG thumbnail stored at
 * {storageBase}/thumbnails/{sha256[0:3]}/{sha256[3:6]}/{sha256}.thumb.jpg The thumbnail storage key
 * is written back to object_versions.thumbnail_key by ProcessingResultPersistenceService.
 *
 * <p>Idempotent: If thumbnail already exists (re-processing), it is overwritten safely.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImageThumbnailProcessor {

  private static final int THUMBNAIL_WIDTH = 300;
  private static final int THUMBNAIL_HEIGHT = 300;
  private static final float JPEG_QUALITY = 0.85f;

  private final MeterRegistry meterRegistry;

  @Value("${vaultflow.storage.base-dir:/data/vaultflow/objects}")
  private String storageBaseDir;

  public FileProcessedEvent process(FileUploadedEvent event) {
    Timer.Sample timer = Timer.start(meterRegistry);

    try {
      Path sourcePath = resolveStoragePath(event.storageKey());
      if (!Files.exists(sourcePath)) {
        return FileProcessedEvent.failed(
            event.objectVersionId(),
            event.objectId(),
            event.bucketId(),
            event.orgId(),
            FileProcessedEvent.ProcessingType.IMAGE_THUMBNAIL,
            "Source file not found: " + event.storageKey());
      }

      String thumbnailKey =
          "thumbnails/" + ChecksumUtil.toStoragePath(event.checksumSha256()) + ".thumb.jpg";
      Path thumbnailPath = Paths.get(storageBaseDir, thumbnailKey);
      Files.createDirectories(thumbnailPath.getParent());

      Thumbnails.of(sourcePath.toFile())
          .size(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)
          .keepAspectRatio(true)
          .outputFormat("JPEG")
          .outputQuality(JPEG_QUALITY)
          .toFile(thumbnailPath.toFile());

      long thumbnailSize = Files.size(thumbnailPath);
      log.info(
          "Thumbnail generated: objectVersionId={} thumbnailKey={} size={}",
          event.objectVersionId(),
          thumbnailKey,
          thumbnailSize);

      meterRegistry.counter("processing.image.thumbnails.generated").increment();
      timer.stop(meterRegistry.timer("processing.image.thumbnail.duration"));

      return FileProcessedEvent.success(
          event.objectVersionId(),
          event.objectId(),
          event.bucketId(),
          event.orgId(),
          FileProcessedEvent.ProcessingType.IMAGE_THUMBNAIL,
          Map.of(
              "thumbnailKey",
              thumbnailKey,
              "thumbnailSize",
              String.valueOf(thumbnailSize),
              "dimensions",
              THUMBNAIL_WIDTH + "x" + THUMBNAIL_HEIGHT));

    } catch (Exception e) {
      log.error(
          "Image thumbnail failed: objectVersionId={} error={}",
          event.objectVersionId(),
          e.getMessage(),
          e);
      meterRegistry.counter("processing.image.thumbnails.failed").increment();
      return FileProcessedEvent.failed(
          event.objectVersionId(),
          event.objectId(),
          event.bucketId(),
          event.orgId(),
          FileProcessedEvent.ProcessingType.IMAGE_THUMBNAIL,
          e.getMessage());
    }
  }

  private Path resolveStoragePath(String storageKey) {
    return Paths.get(storageBaseDir, storageKey);
  }
}
