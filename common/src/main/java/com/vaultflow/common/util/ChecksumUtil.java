package com.vaultflow.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.commons.codec.binary.Hex;

/**
 * Utility for computing SHA-256 checksums during upload and verification.
 *
 * <p>Why SHA-256? MD5 and SHA-1 have collision vulnerabilities. SHA-256 provides: - Collision
 * resistance suitable for deduplication keys - Sufficient entropy for 5B+ objects without collision
 * risk - Industry standard (used by S3 ETag for multipart, Git object store, Docker layers)
 *
 * <p>Implementation note: We use DigestInputStream to compute hash in a single streaming pass over
 * the upload data, avoiding loading the file into memory. This is critical for files exceeding JVM
 * heap size.
 */
public final class ChecksumUtil {

  private ChecksumUtil() {}

  /**
   * Compute SHA-256 of the given byte array. Used for individual chunk validation where the chunk
   * is already buffered.
   */
  public static String sha256Hex(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return Hex.encodeHexString(digest.digest(data));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed by JVM spec — this is unreachable
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** Compute SHA-256 of the given string (used for small metadata like tokens). */
  public static String sha256Hex(String data) {
    return sha256Hex(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * Streaming SHA-256 computation. Reads the stream in 8KB chunks, computing the digest
   * incrementally. Returns the digest and leaves the stream position at EOF.
   *
   * @param inputStream stream to hash (not closed by this method)
   * @return hex-encoded SHA-256 digest
   */
  public static String sha256HexStreaming(InputStream inputStream) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (DigestInputStream dis = new DigestInputStream(inputStream, digest)) {
        byte[] buffer = new byte[8192];
        while (dis.read(buffer) != -1) {
          // digest is updated by DigestInputStream as we read
        }
      }
      return Hex.encodeHexString(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /**
   * Verify that actual checksum matches expected. Throws IllegalArgumentException on mismatch to
   * signal data corruption or client tampering.
   */
  public static void verify(String expected, String actual) {
    if (!expected.equalsIgnoreCase(actual)) {
      throw new IllegalArgumentException(
          String.format("Checksum mismatch: expected=%s actual=%s", expected, actual));
    }
  }

  /**
   * Generate a content-addressed storage key from SHA-256 hash. Uses two-level directory sharding
   * to avoid inode exhaustion on filesystems that degrade with millions of files per directory.
   *
   * <p>Example: abc123def456... → abc/12/abc123def456...
   */
  public static String toStoragePath(String sha256Hex) {
    if (sha256Hex == null || sha256Hex.length() < 6) {
      throw new IllegalArgumentException("Invalid SHA-256 hex: " + sha256Hex);
    }
    return sha256Hex.substring(0, 3) + "/" + sha256Hex.substring(3, 6) + "/" + sha256Hex;
  }
}
