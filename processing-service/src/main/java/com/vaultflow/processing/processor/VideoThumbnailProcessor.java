package com.vaultflow.processing.processor;

import com.vaultflow.common.event.FileProcessedEvent;
import com.vaultflow.common.event.FileUploadedEvent;
import com.vaultflow.common.util.ChecksumUtil;
import io.micrometer.core.instrument.MeterRegistry;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Video thumbnail processor.
 *
 * <p>Production: Integrate FFmpeg via ProcessBuilder to extract frame at 1s mark: {@code ffmpeg -i
 * input.mp4 -ss 00:00:01 -vframes 1 -vf scale=300:300 output.jpg}
 *
 * <p>Simulation: Generates a placeholder thumbnail image with video metadata overlay. Full FFmpeg
 * integration requires FFmpeg binary in the container image (multi-stage Dockerfile). The interface
 * is identical — swap the implementation without changing the orchestrator.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoThumbnailProcessor {

  private final MeterRegistry meterRegistry;

  @Value("${vaultflow.storage.base-dir:/data/vaultflow/objects}")
  private String storageBaseDir;

  public FileProcessedEvent process(FileUploadedEvent event) {
    try {
      String thumbnailKey =
          "thumbnails/" + ChecksumUtil.toStoragePath(event.checksumSha256()) + ".video-thumb.jpg";
      Path thumbnailPath = Paths.get(storageBaseDir, thumbnailKey);
      Files.createDirectories(thumbnailPath.getParent());

      // Simulation: create placeholder thumbnail
      BufferedImage img = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = img.createGraphics();
      g.setColor(new Color(30, 30, 30));
      g.fillRect(0, 0, 300, 300);
      g.setColor(Color.WHITE);
      g.setFont(new Font("Arial", Font.BOLD, 16));
      g.drawString("VIDEO", 120, 140);
      g.drawString(
          event.objectKey().substring(Math.max(0, event.objectKey().length() - 20)), 20, 170);
      g.dispose();
      ImageIO.write(img, "JPEG", thumbnailPath.toFile());

      meterRegistry.counter("processing.video.thumbnails.generated").increment();
      return FileProcessedEvent.success(
          event.objectVersionId(),
          event.objectId(),
          event.bucketId(),
          event.orgId(),
          FileProcessedEvent.ProcessingType.VIDEO_THUMBNAIL,
          Map.of("thumbnailKey", thumbnailKey, "simulated", "true"));

    } catch (Exception e) {
      log.error("Video thumbnail failed: {}", e.getMessage(), e);
      return FileProcessedEvent.failed(
          event.objectVersionId(),
          event.objectId(),
          event.bucketId(),
          event.orgId(),
          FileProcessedEvent.ProcessingType.VIDEO_THUMBNAIL,
          e.getMessage());
    }
  }
}
