package com.vaultflow.upload.storage;

import com.vaultflow.common.exception.StorageException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local filesystem implementation of {@link ObjectStoragePort}.
 *
 * <p>Production considerations: - Objects stored in content-addressed layout:
 * {base}/{hash[0:3]}/{hash[3:6]}/{full-hash} - Parts stored under:
 * {base}/tmp/sessions/{sessionId}/part_{number} - Atomic writes via temp file + rename (prevents
 * partial reads during concurrent access) - Pre-allocates directory structure to prevent inode
 * exhaustion
 *
 * <p>Limitations vs S3: - Single machine — not distributed, not replicated - No built-in redundancy
 *
 * <p>Swap path: Replace this bean with S3CompatibleStorage for production cloud deployments. The
 * ObjectStoragePort interface hides all S3-specific API — zero business logic changes needed.
 */
@Component
@Slf4j
public class LocalFileSystemStorage implements ObjectStoragePort {

  private final Path baseDir;
  private final Path tempDir;
  private final MeterRegistry meterRegistry;

  public LocalFileSystemStorage(
      @Value("${vaultflow.storage.base-dir:/data/vaultflow/objects}") String basePath,
      MeterRegistry meterRegistry)
      throws IOException {
    this.baseDir = Paths.get(basePath);
    this.tempDir = this.baseDir.resolve("tmp/sessions");
    this.meterRegistry = meterRegistry;
    Files.createDirectories(this.baseDir);
    Files.createDirectories(this.tempDir);
    log.info("Local storage initialized at: {}", this.baseDir.toAbsolutePath());
  }

  @Override
  public String store(String storageKey, InputStream data, long sizeBytes, String contentType) {
    Timer.Sample sample = Timer.start(meterRegistry);
    Path targetPath = resolvePath(storageKey);

    // Idempotent: if already stored (dedup hit), return key without writing
    if (Files.exists(targetPath)) {
      log.debug("Dedup hit for storageKey={}, skipping write", storageKey);
      meterRegistry.counter("storage.dedup.hits").increment();
      return storageKey;
    }

    try {
      Files.createDirectories(targetPath.getParent());
      // Atomic write: write to temp file then rename — prevents partial reads
      Path tempFile = targetPath.getParent().resolve("." + targetPath.getFileName() + ".tmp");
      try (OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.CREATE_NEW)) {
        long written = IOUtils.copyLarge(data, out);
        log.debug("Wrote {} bytes for storageKey={}", written, storageKey);
        meterRegistry.counter("storage.bytes.written").increment(written);
      }
      // Atomic rename — POSIX guarantee: rename is atomic on same filesystem
      Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE);
      sample.stop(meterRegistry.timer("storage.write.duration"));
      return storageKey;
    } catch (IOException e) {
      throw new StorageException("Failed to store object: " + storageKey, e);
    }
  }

  @Override
  public String storePart(String sessionId, int partNumber, InputStream data, long sizeBytes) {
    String partKey = "part_" + String.format("%05d", partNumber);
    Path partPath = tempDir.resolve(sessionId).resolve(partKey);

    try {
      Files.createDirectories(partPath.getParent());
      try (OutputStream out =
          Files.newOutputStream(
              partPath,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING)) {
        IOUtils.copyLarge(data, out);
      }
      log.debug("Stored part sessionId={} partNumber={}", sessionId, partNumber);
      return partKey;
    } catch (IOException e) {
      throw new StorageException(
          "Failed to store part " + partNumber + " for session " + sessionId, e);
    }
  }

  @Override
  public long assembleParts(String storageKey, String sessionId, List<String> partKeys) {
    Path targetPath = resolvePath(storageKey);
    Path sessionDir = tempDir.resolve(sessionId);

    try {
      Files.createDirectories(targetPath.getParent());
      Path tempFile =
          targetPath.getParent().resolve("." + targetPath.getFileName() + ".assembling");

      long totalBytes = 0;
      try (OutputStream out =
          new BufferedOutputStream(
              Files.newOutputStream(tempFile, StandardOpenOption.CREATE_NEW), 1024 * 1024)) {
        for (String partKey : partKeys) {
          Path partPath = sessionDir.resolve(partKey);
          if (!Files.exists(partPath)) {
            throw new StorageException("Missing part during assembly: " + partKey);
          }
          try (InputStream in = new BufferedInputStream(Files.newInputStream(partPath))) {
            totalBytes += IOUtils.copyLarge(in, out);
          }
        }
      }

      Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE);

      // Cleanup temp parts
      for (String partKey : partKeys) {
        Files.deleteIfExists(sessionDir.resolve(partKey));
      }
      Files.deleteIfExists(sessionDir);

      log.info(
          "Assembled {} parts for storageKey={}, totalBytes={}",
          partKeys.size(),
          storageKey,
          totalBytes);
      meterRegistry.counter("storage.bytes.written").increment(totalBytes);
      return totalBytes;

    } catch (IOException e) {
      throw new StorageException("Failed to assemble parts for: " + storageKey, e);
    }
  }

  @Override
  public InputStream retrieve(String storageKey) {
    Path path = resolvePath(storageKey);
    if (!Files.exists(path)) {
      throw new StorageException("Object not found: " + storageKey);
    }
    try {
      meterRegistry.counter("storage.reads.total").increment();
      return new BufferedInputStream(Files.newInputStream(path), 64 * 1024);
    } catch (IOException e) {
      throw new StorageException("Failed to retrieve object: " + storageKey, e);
    }
  }

  @Override
  public InputStream retrieveRange(String storageKey, long offset, long length) {
    Path path = resolvePath(storageKey);
    if (!Files.exists(path)) {
      throw new StorageException("Object not found: " + storageKey);
    }
    try {
      RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
      raf.seek(offset);
      long readLength = length < 0 ? raf.length() - offset : length;
      // Wrap in stream that limits reads to requested range
      return new BoundedInputStream(new FileInputStream(raf.getFD()), readLength);
    } catch (IOException e) {
      throw new StorageException("Failed to retrieve range from: " + storageKey, e);
    }
  }

  @Override
  public void delete(String storageKey) {
    Path path = resolvePath(storageKey);
    try {
      boolean deleted = Files.deleteIfExists(path);
      if (deleted) {
        log.info("Deleted object: storageKey={}", storageKey);
        meterRegistry.counter("storage.deletes.total").increment();
      }
    } catch (IOException e) {
      throw new StorageException("Failed to delete object: " + storageKey, e);
    }
  }

  @Override
  public void deletePart(String sessionId, int partNumber, String partKey) {
    Path partPath = tempDir.resolve(sessionId).resolve(partKey);
    try {
      Files.deleteIfExists(partPath);
    } catch (IOException e) {
      log.warn("Failed to delete temp part: sessionId={} partKey={}", sessionId, partKey, e);
    }
  }

  @Override
  public boolean exists(String storageKey) {
    return Files.exists(resolvePath(storageKey));
  }

  @Override
  public long getSize(String storageKey) {
    try {
      return Files.size(resolvePath(storageKey));
    } catch (IOException e) {
      throw new StorageException("Failed to get size for: " + storageKey, e);
    }
  }

  private Path resolvePath(String storageKey) {
    // storageKey is already content-addressed: abc/def/abcdef...
    // Validate to prevent path traversal attacks
    if (storageKey.contains("..") || storageKey.startsWith("/")) {
      throw new StorageException("Invalid storage key: " + storageKey);
    }
    return baseDir.resolve(storageKey).normalize();
  }

  /** Limits reads from an InputStream to a maximum number of bytes. */
  private static class BoundedInputStream extends FilterInputStream {
    private long remaining;

    BoundedInputStream(InputStream in, long limit) {
      super(in);
      this.remaining = limit;
    }

    @Override
    public int read() throws IOException {
      if (remaining <= 0) return -1;
      int b = super.read();
      if (b >= 0) remaining--;
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (remaining <= 0) return -1;
      int toRead = (int) Math.min(len, remaining);
      int n = super.read(b, off, toRead);
      if (n > 0) remaining -= n;
      return n;
    }
  }
}
