package com.vaultflow.upload.util;

import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

/**
 * Detects actual content type by inspecting file magic bytes via Apache Tika.
 *
 * <p>Why not trust client-provided Content-Type? Clients can (accidentally or maliciously) send
 * wrong content types. A .exe uploaded as image/png could bypass security controls downstream. Tika
 * reads the first few bytes (magic numbers) to determine the actual type, independent of filename
 * extension or client header.
 *
 * <p>We still respect client-provided type when Tika cannot detect, falling back gracefully.
 */
@Component
@Slf4j
public class ContentTypeDetector {

  private static final Tika TIKA = new Tika();
  private static final String DEFAULT_TYPE = "application/octet-stream";

  /**
   * Detect content type. Returns detected type if confident, otherwise falls back to client-
   * provided type, then to octet-stream.
   */
  public String detect(InputStream data, String clientContentType, String filename) {
    try {
      // Tika reads only the first ~8KB (magic bytes) — does not consume the full stream
      String detected = TIKA.detect(data, filename);
      if (detected != null && !DEFAULT_TYPE.equals(detected)) {
        if (clientContentType != null && !clientContentType.equals(detected)) {
          log.warn(
              "Content type mismatch: client={} detected={} filename={}",
              clientContentType,
              detected,
              filename);
        }
        return detected;
      }
    } catch (Exception e) {
      log.debug("Tika detection failed for filename={}: {}", filename, e.getMessage());
    }

    return clientContentType != null && !clientContentType.isBlank()
        ? clientContentType
        : DEFAULT_TYPE;
  }

  /** Check if the content type represents an image. */
  public static boolean isImage(String contentType) {
    return contentType != null && contentType.startsWith("image/");
  }

  /** Check if the content type represents a video. */
  public static boolean isVideo(String contentType) {
    return contentType != null && contentType.startsWith("video/");
  }

  /** Check if the content type represents a PDF document. */
  public static boolean isPdf(String contentType) {
    return "application/pdf".equalsIgnoreCase(contentType);
  }
}
