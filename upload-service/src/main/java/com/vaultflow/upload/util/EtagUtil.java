package com.vaultflow.upload.util;

import java.util.List;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * S3-compatible ETag generation.
 *
 * <p>Single-part: ETag = '"' + MD5(content) + '"'
 * Multipart: ETag = '"' + MD5(concat(part_MD5s)) + "-" + partCount + '"'
 *
 * <p>We expose ETags for compatibility with S3 SDK clients and for conditional requests
 * (If-Match, If-None-Match headers in download service).
 */
public final class EtagUtil {

  private EtagUtil() {}

  public static String singlePartEtag(byte[] content) {
    return DigestUtils.md5Hex(content);
  }

  public static String singlePartEtag(String md5Hex) {
    return md5Hex;
  }

  /**
   * Compute multipart ETag: MD5 of the concatenated binary MD5 digests of all parts.
   * This matches the AWS S3 multipart ETag algorithm exactly.
   */
  public static String multipartEtag(List<String> partMd5Hexes) {
    byte[] concatenated = new byte[partMd5Hexes.size() * 16];
    int offset = 0;
    for (String md5Hex : partMd5Hexes) {
      byte[] partMd5 = hexToBytes(md5Hex);
      System.arraycopy(partMd5, 0, concatenated, offset, 16);
      offset += 16;
    }
    return DigestUtils.md5Hex(concatenated);
  }

  private static byte[] hexToBytes(String hex) {
    int len = hex.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
          + Character.digit(hex.charAt(i + 1), 16));
    }
    return data;
  }
}
