package com.vaultflow.upload.storage;

import static org.assertj.core.api.Assertions.*;

import com.vaultflow.common.exception.StorageException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("LocalFileSystemStorage")
class LocalFileSystemStorageTest {

  @TempDir Path tempDir;

  LocalFileSystemStorage storage;

  @BeforeEach
  void setUp() throws IOException {
    storage = new LocalFileSystemStorage(tempDir.toString(), new SimpleMeterRegistry());
  }

  @Nested
  @DisplayName("store")
  class Store {

    @Test
    @DisplayName("writes file and returns storage key")
    void storesFile() throws IOException {
      byte[] data = "hello world content".getBytes(StandardCharsets.UTF_8);
      String key = "abc/def/abcdef1234";

      String returned =
          storage.store(key, new ByteArrayInputStream(data), data.length, "text/plain");

      assertThat(returned).isEqualTo(key);
      assertThat(storage.exists(key)).isTrue();
      assertThat(storage.getSize(key)).isEqualTo(data.length);
    }

    @Test
    @DisplayName("is idempotent — second write of same key returns without overwriting")
    void idempotentOnDuplicate() throws IOException {
      byte[] data = "original content".getBytes();
      String key = "abc/def/dupkey";

      storage.store(key, new ByteArrayInputStream(data), data.length, "text/plain");

      // Read original content to verify idempotency
      byte[] originalContent;
      try (InputStream in = storage.retrieve(key)) {
        originalContent = in.readAllBytes();
      }

      // Second store with different data — should be ignored (dedup)
      byte[] differentData = "different content".getBytes();
      storage.store(
          key, new ByteArrayInputStream(differentData), differentData.length, "text/plain");

      byte[] afterSecondStore;
      try (InputStream in = storage.retrieve(key)) {
        afterSecondStore = in.readAllBytes();
      }

      assertThat(afterSecondStore).isEqualTo(originalContent);
    }

    @Test
    @DisplayName("rejects path traversal attempts")
    void rejectsPathTraversal() {
      assertThatThrownBy(
              () ->
                  storage.store(
                      "../../../etc/passwd",
                      new ByteArrayInputStream(new byte[0]),
                      0,
                      "text/plain"))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Invalid storage key");
    }

    @Test
    @DisplayName("rejects absolute path keys")
    void rejectsAbsolutePaths() {
      assertThatThrownBy(
              () ->
                  storage.store(
                      "/etc/passwd", new ByteArrayInputStream(new byte[0]), 0, "text/plain"))
          .isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("creates nested directory structure")
    void createsDirectories() throws IOException {
      String key = "a1b/c2d/e3f456789012345678901234567890123456789012345678901234";
      byte[] data = "test".getBytes();

      storage.store(key, new ByteArrayInputStream(data), data.length, "text/plain");

      assertThat(storage.exists(key)).isTrue();
      assertThat(Files.isDirectory(tempDir.resolve("a1b").resolve("c2d"))).isTrue();
    }
  }

  @Nested
  @DisplayName("retrieve")
  class Retrieve {

    @Test
    @DisplayName("returns correct content")
    void retrievesContent() throws IOException {
      byte[] data = "retrieve me".getBytes(StandardCharsets.UTF_8);
      String key = "abc/def/retrievekey";
      storage.store(key, new ByteArrayInputStream(data), data.length, "text/plain");

      try (InputStream in = storage.retrieve(key)) {
        byte[] result = in.readAllBytes();
        assertThat(result).isEqualTo(data);
      }
    }

    @Test
    @DisplayName("throws StorageException for missing key")
    void throwsForMissingKey() {
      assertThatThrownBy(() -> storage.retrieve("abc/def/nonexistent"))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Object not found");
    }
  }

  @Nested
  @DisplayName("retrieveRange")
  class RetrieveRange {

    @Test
    @DisplayName("returns correct byte range")
    void rangeRead() throws IOException {
      byte[] data = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
      String key = "abc/def/rangekey";
      storage.store(key, new ByteArrayInputStream(data), data.length, "text/plain");

      try (InputStream in = storage.retrieveRange(key, 4, 6)) {
        byte[] result = in.readAllBytes();
        assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo("456789");
      }
    }

    @Test
    @DisplayName("offset 0 with length -1 returns full file")
    void fullFileWithNegativeLength() throws IOException {
      byte[] data = "full content".getBytes();
      String key = "abc/def/fullkey";
      storage.store(key, new ByteArrayInputStream(data), data.length, "text/plain");

      try (InputStream in = storage.retrieveRange(key, 0, -1)) {
        assertThat(in.readAllBytes()).isEqualTo(data);
      }
    }
  }

  @Nested
  @DisplayName("assembleParts")
  class AssembleParts {

    @Test
    @DisplayName("concatenates parts in order into final object")
    void assemblesParts() throws IOException {
      String sessionId = "test-session-123";
      byte[] part1 = "Hello, ".getBytes();
      byte[] part2 = "World".getBytes();
      byte[] part3 = "!".getBytes();

      String partKey1 =
          storage.storePart(sessionId, 1, new ByteArrayInputStream(part1), part1.length);
      String partKey2 =
          storage.storePart(sessionId, 2, new ByteArrayInputStream(part2), part2.length);
      String partKey3 =
          storage.storePart(sessionId, 3, new ByteArrayInputStream(part3), part3.length);

      String finalKey = "abc/def/assembledfile";
      long totalBytes =
          storage.assembleParts(finalKey, sessionId, List.of(partKey1, partKey2, partKey3));

      assertThat(totalBytes).isEqualTo(part1.length + part2.length + part3.length);
      assertThat(storage.exists(finalKey)).isTrue();

      try (InputStream in = storage.retrieve(finalKey)) {
        assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
            .isEqualTo("Hello, World!");
      }
    }

    @Test
    @DisplayName("cleans up temp parts after assembly")
    void cleansUpParts() throws IOException {
      String sessionId = "cleanup-session";
      byte[] data = "part data".getBytes();
      String partKey = storage.storePart(sessionId, 1, new ByteArrayInputStream(data), data.length);

      storage.assembleParts("abc/def/final", sessionId, List.of(partKey));

      // Session temp directory should be gone
      assertThat(Files.exists(tempDir.resolve("tmp/sessions").resolve(sessionId))).isFalse();
    }
  }

  @Nested
  @DisplayName("delete")
  class Delete {

    @Test
    @DisplayName("removes file from storage")
    void deletesFile() throws IOException {
      String key = "abc/def/deletekey";
      byte[] data = "delete me".getBytes();
      storage.store(key, new ByteArrayInputStream(data), data.length, "text/plain");

      assertThat(storage.exists(key)).isTrue();
      storage.delete(key);
      assertThat(storage.exists(key)).isFalse();
    }

    @Test
    @DisplayName("delete of non-existent key is silent (no exception)")
    void deleteNonExistentIsIdempotent() {
      assertThatNoException().isThrownBy(() -> storage.delete("abc/def/nonexistent"));
    }
  }
}
