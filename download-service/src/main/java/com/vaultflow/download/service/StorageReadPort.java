package com.vaultflow.download.service;

import com.vaultflow.common.exception.StorageException;
import java.io.*;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Storage read adapter for the download service.
 * Mirrors LocalFileSystemStorage but is scoped to read operations only.
 * In production this would delegate to S3 GetObject with range support.
 */
@Component
public class StorageReadPort {

  private final Path baseDir;

  public StorageReadPort(
      @Value("${vaultflow.storage.base-dir:/data/vaultflow/objects}") String basePath) throws IOException {
    this.baseDir = Paths.get(basePath);
    Files.createDirectories(this.baseDir);
  }

  public InputStream retrieve(String storageKey) {
    Path path = resolve(storageKey);
    if (!Files.exists(path)) throw new StorageException("Object not found: " + storageKey);
    try {
      return new BufferedInputStream(Files.newInputStream(path), 64 * 1024);
    } catch (IOException e) {
      throw new StorageException("Failed to open object: " + storageKey, e);
    }
  }

  public InputStream retrieveRange(String storageKey, long offset, long length) {
    Path path = resolve(storageKey);
    if (!Files.exists(path)) throw new StorageException("Object not found: " + storageKey);
    try {
      RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
      raf.seek(offset);
      long readLength = length < 0 ? raf.length() - offset : Math.min(length, raf.length() - offset);
      InputStream base = new FileInputStream(raf.getFD());
      // Limit stream to requested range
      return new FilterInputStream(base) {
        private long remaining = readLength;
        public int read() throws IOException {
          if (remaining <= 0) return -1;
          int b = super.read(); if (b >= 0) remaining--; return b;
        }
        public int read(byte[] b, int off, int len) throws IOException {
          if (remaining <= 0) return -1;
          int n = super.read(b, off, (int) Math.min(len, remaining));
          if (n > 0) remaining -= n; return n;
        }
      };
    } catch (IOException e) {
      throw new StorageException("Failed to read range: " + storageKey, e);
    }
  }

  public long getSize(String storageKey) {
    try { return Files.size(resolve(storageKey)); }
    catch (IOException e) { throw new StorageException("Size check failed: " + storageKey, e); }
  }

  private Path resolve(String key) {
    if (key.contains("..") || key.startsWith("/")) throw new StorageException("Invalid key: " + key);
    return baseDir.resolve(key).normalize();
  }
}
