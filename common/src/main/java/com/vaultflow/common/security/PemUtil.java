package com.vaultflow.common.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for loading PEM-encoded RSA keys.
 *
 * <p>Supports both PKCS#8 private keys (BEGIN PRIVATE KEY) and SPKI public keys (BEGIN PUBLIC KEY).
 * This is the standard format produced by OpenSSL and most key management tools.
 */
public final class PemUtil {

  private static final Logger log = LoggerFactory.getLogger(PemUtil.class);

  private static final Pattern PUBLIC_KEY_PATTERN =
      Pattern.compile(
          "-+BEGIN\\s+PUBLIC\\s+KEY-+\\s*(.*?)\\s*-+END\\s+PUBLIC\\s+KEY-+", Pattern.DOTALL);
  private static final Pattern PRIVATE_KEY_PATTERN =
      Pattern.compile(
          "-+BEGIN\\s+PRIVATE\\s+KEY-+\\s*(.*?)\\s*-+END\\s+PRIVATE\\s+KEY-+", Pattern.DOTALL);

  private PemUtil() {}

  /** Load an RSA public key from a PEM file path. */
  public static PublicKey loadPublicKey(Path path) throws IOException {
    String pem = Files.readString(path, StandardCharsets.UTF_8);
    return parsePublicKey(pem);
  }

  /** Load an RSA public key from a classpath resource. */
  public static PublicKey loadPublicKeyFromClasspath(String resourcePath) throws IOException {
    try (InputStream is = PemUtil.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IOException("Public key resource not found: " + resourcePath);
      }
      String pem = readStream(is);
      return parsePublicKey(pem);
    }
  }

  /** Load an RSA private key from a PEM file path. */
  public static PrivateKey loadPrivateKey(Path path) throws IOException {
    String pem = Files.readString(path, StandardCharsets.UTF_8);
    return parsePrivateKey(pem);
  }

  /** Load an RSA private key from a classpath resource. */
  public static PrivateKey loadPrivateKeyFromClasspath(String resourcePath) throws IOException {
    try (InputStream is = PemUtil.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IOException("Private key resource not found: " + resourcePath);
      }
      String pem = readStream(is);
      return parsePrivateKey(pem);
    }
  }

  public static PublicKey parsePublicKey(String pem) {
    try {
      Matcher m = PUBLIC_KEY_PATTERN.matcher(pem);
      if (!m.find()) {
        throw new IllegalArgumentException("No public key found in PEM data");
      }
      String base64 = m.group(1).replaceAll("\\s+", "");
      byte[] der = Base64.getMimeDecoder().decode(base64);
      X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
      KeyFactory kf = KeyFactory.getInstance("RSA");
      return kf.generatePublic(spec);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalArgumentException("Invalid RSA public key", e);
    }
  }

  public static PrivateKey parsePrivateKey(String pem) {
    try {
      Matcher m = PRIVATE_KEY_PATTERN.matcher(pem);
      if (!m.find()) {
        throw new IllegalArgumentException("No private key found in PEM data");
      }
      String base64 = m.group(1).replaceAll("\\s+", "");
      byte[] der = Base64.getMimeDecoder().decode(base64);
      PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
      KeyFactory kf = KeyFactory.getInstance("RSA");
      return kf.generatePrivate(spec);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalArgumentException("Invalid RSA private key", e);
    }
  }

  private static String readStream(InputStream is) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = is.read(buf)) != -1) {
      baos.write(buf, 0, n);
    }
    return baos.toString(StandardCharsets.UTF_8);
  }
}
