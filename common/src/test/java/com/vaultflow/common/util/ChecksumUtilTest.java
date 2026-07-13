package com.vaultflow.common.util;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ChecksumUtil")
class ChecksumUtilTest {

  @Nested
  @DisplayName("sha256Hex(byte[])")
  class Sha256ByteArray {

    @Test
    @DisplayName("produces correct SHA-256 for known input")
    void knownHash() {
      // SHA-256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
      String hash = ChecksumUtil.sha256Hex("hello".getBytes(StandardCharsets.UTF_8));
      assertThat(hash)
          .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    @DisplayName("returns 64 hex characters")
    void hashLength() {
      String hash = ChecksumUtil.sha256Hex("test".getBytes());
      assertThat(hash).hasSize(64);
    }

    @Test
    @DisplayName("same input produces same hash (deterministic)")
    void deterministic() {
      byte[] data = "same content".getBytes();
      assertThat(ChecksumUtil.sha256Hex(data)).isEqualTo(ChecksumUtil.sha256Hex(data));
    }

    @Test
    @DisplayName("different content produces different hash")
    void collision() {
      assertThat(ChecksumUtil.sha256Hex("foo".getBytes()))
          .isNotEqualTo(ChecksumUtil.sha256Hex("bar".getBytes()));
    }
  }

  @Nested
  @DisplayName("sha256HexStreaming")
  class Sha256Streaming {

    @Test
    @DisplayName("streaming hash matches byte array hash")
    void streamingMatchesBatch() throws IOException {
      byte[] data = "stream test content for checksumming".getBytes();
      String batchHash = ChecksumUtil.sha256Hex(data);
      String streamHash = ChecksumUtil.sha256HexStreaming(new ByteArrayInputStream(data));
      assertThat(streamHash).isEqualTo(batchHash);
    }

    @Test
    @DisplayName("empty stream produces correct hash")
    void emptyStream() throws IOException {
      // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
      String hash = ChecksumUtil.sha256HexStreaming(new ByteArrayInputStream(new byte[0]));
      assertThat(hash)
          .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
  }

  @Nested
  @DisplayName("toStoragePath")
  class StoragePath {

    @Test
    @DisplayName("creates two-level sharded path")
    void twoLevelSharding() {
      String hash = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
      String path = ChecksumUtil.toStoragePath(hash);
      assertThat(path)
          .isEqualTo("abc/def/abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890");
    }

    @Test
    @DisplayName("throws for null hash")
    void nullHash() {
      assertThatThrownBy(() -> ChecksumUtil.toStoragePath(null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("throws for short hash")
    void shortHash() {
      assertThatThrownBy(() -> ChecksumUtil.toStoragePath("abc"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("first 3 chars become top-level dir, next 3 become second-level dir")
    void dirStructure() {
      String hash = "aabbcc" + "a".repeat(58);
      String path = ChecksumUtil.toStoragePath(hash);
      String[] parts = path.split("/");
      assertThat(parts[0]).isEqualTo("aab");
      assertThat(parts[1]).isEqualTo("bcc");
      assertThat(parts[2]).isEqualTo(hash);
    }
  }

  @Nested
  @DisplayName("verify")
  class Verify {

    @Test
    @DisplayName("passes when checksums match (case-insensitive)")
    void checksumMatch() {
      assertThatNoException().isThrownBy(() -> ChecksumUtil.verify("ABCDEF", "abcdef"));
    }

    @Test
    @DisplayName("throws when checksums differ")
    void checksumMismatch() {
      assertThatThrownBy(() -> ChecksumUtil.verify("abc123", "def456"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Checksum mismatch");
    }
  }
}
