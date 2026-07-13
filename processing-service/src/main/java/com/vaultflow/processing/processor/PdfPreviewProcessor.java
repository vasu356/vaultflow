package com.vaultflow.processing.processor;

import com.vaultflow.common.event.FileProcessedEvent;
import com.vaultflow.common.event.FileUploadedEvent;
import com.vaultflow.common.util.ChecksumUtil;
import io.micrometer.core.instrument.MeterRegistry;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Generates a PNG preview image of the first page of a PDF document.
 *
 * <p>Uses PDFBox 3.x (Loader.loadPDF API). Renders at 150 DPI — sufficient for thumbnail quality
 * while keeping memory usage bounded. A 150 DPI render of a US Letter page = ~1240×1754 pixels ≈ 8
 * MB BufferedImage. Acceptable for processing service pods with 512 MB heap.
 *
 * <p>For very large PDFs (>1000 pages), we only render page 1 — page-level rendering is lazy in
 * PDFBox (only the requested page is decoded).
 *
 * <p>Security: PDFBox does not execute JavaScript or external resources. XFA forms are not rendered
 * (not supported in PDFBox 3.x — mitigates XFA-based attacks).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PdfPreviewProcessor {

  private static final float RENDER_DPI = 150f;

  private final MeterRegistry meterRegistry;

  @Value("${vaultflow.storage.base-dir:/data/vaultflow/objects}")
  private String storageBaseDir;

  public FileProcessedEvent process(FileUploadedEvent event) {
    try {
      Path sourcePath = Paths.get(storageBaseDir, event.storageKey());
      if (!Files.exists(sourcePath)) {
        return FileProcessedEvent.failed(
            event.objectVersionId(),
            event.objectId(),
            event.bucketId(),
            event.orgId(),
            FileProcessedEvent.ProcessingType.PDF_PREVIEW,
            "Source PDF not found");
      }

      String previewKey =
          "previews/" + ChecksumUtil.toStoragePath(event.checksumSha256()) + ".preview.png";
      Path previewPath = Paths.get(storageBaseDir, previewKey);
      Files.createDirectories(previewPath.getParent());

      try (PDDocument document = Loader.loadPDF(sourcePath.toFile())) {
        int pageCount = document.getNumberOfPages();
        PDFRenderer renderer = new PDFRenderer(document);
        BufferedImage image = renderer.renderImageWithDPI(0, RENDER_DPI, ImageType.RGB);
        ImageIO.write(image, "PNG", previewPath.toFile());

        long previewSize = Files.size(previewPath);
        log.info(
            "PDF preview generated: objectVersionId={} pages={} previewKey={}",
            event.objectVersionId(),
            pageCount,
            previewKey);

        meterRegistry.counter("processing.pdf.previews.generated").increment();
        return FileProcessedEvent.success(
            event.objectVersionId(),
            event.objectId(),
            event.bucketId(),
            event.orgId(),
            FileProcessedEvent.ProcessingType.PDF_PREVIEW,
            Map.of(
                "previewKey",
                previewKey,
                "pageCount",
                String.valueOf(pageCount),
                "previewSize",
                String.valueOf(previewSize)));
      }
    } catch (Exception e) {
      log.error(
          "PDF preview failed: objectVersionId={} error={}",
          event.objectVersionId(),
          e.getMessage(),
          e);
      meterRegistry.counter("processing.pdf.previews.failed").increment();
      return FileProcessedEvent.failed(
          event.objectVersionId(),
          event.objectId(),
          event.bucketId(),
          event.orgId(),
          FileProcessedEvent.ProcessingType.PDF_PREVIEW,
          e.getMessage());
    }
  }
}
