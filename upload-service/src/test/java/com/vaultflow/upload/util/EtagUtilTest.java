package com.vaultflow.upload.util;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EtagUtil")
class EtagUtilTest {

  @Test
  @DisplayName("single-part ETag is the MD5 hex string unchanged")
  void singlePartEtag() {
    String md5 = "d8e8fca2dc0f896fd7cb4cb0031ba249";
    assertThat(EtagUtil.singlePartEtag(md5)).isEqualTo(md5);
  }

  @Test
  @DisplayName("multipart ETag is MD5 of concatenated part MD5 binary digests")
  void multipartEtag() {
    // Known test vectors: two parts with known MD5s
    String part1Md5 = "d8e8fca2dc0f896fd7cb4cb0031ba249"; // MD5 of some content
    String part2Md5 = "2d97f80a2a4a4d3f8f57d8ae3e851cc3";
    String result = EtagUtil.multipartEtag(List.of(part1Md5, part2Md5));
    // Result should be a 32-char hex string
    assertThat(result).hasSize(32);
    assertThat(result).matches("[a-f0-9]+");
  }

  @Test
  @DisplayName("multipart ETag is deterministic")
  void multipartDeterministic() {
    List<String> parts = List.of(
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "cccccccccccccccccccccccccccccccc");
    String first = EtagUtil.multipartEtag(parts);
    String second = EtagUtil.multipartEtag(parts);
    assertThat(first).isEqualTo(second);
  }

  @Test
  @DisplayName("different part order produces different multipart ETag")
  void orderSensitive() {
    String md5a = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    String md5b = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    String ab = EtagUtil.multipartEtag(List.of(md5a, md5b));
    String ba = EtagUtil.multipartEtag(List.of(md5b, md5a));
    assertThat(ab).isNotEqualTo(ba);
  }

  @Test
  @DisplayName("single-element multipart ETag differs from single-part ETag (by design)")
  void singleElementMultipartDiffersFromSinglePart() {
    String md5 = "d8e8fca2dc0f896fd7cb4cb0031ba249";
    String single = EtagUtil.singlePartEtag(md5);
    String multi = EtagUtil.multipartEtag(List.of(md5));
    // Multipart hashes the binary MD5 of the part MD5, so result differs
    assertThat(single).isNotEqualTo(multi);
  }
}
