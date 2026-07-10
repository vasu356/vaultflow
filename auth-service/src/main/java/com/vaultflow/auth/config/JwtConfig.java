package com.vaultflow.auth.config;

import com.vaultflow.common.security.JwtTokenProvider;
import com.vaultflow.common.security.PemUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JWT RSA key configuration.
 *
 * <p>Loads the shared RSA key pair from PEM files mounted at {@code vaultflow.jwt.private-key-path}
 * and {@code vaultflow.jwt.public-key-path}. In Docker Compose, these are mounted from the
 * {@code keys/} directory via a shared volume.
 *
 * <p>The auth-service has both private and public keys and can sign and validate tokens.
 * Resource services (upload, download, admin, metadata) only load the public key and can
 * validate but not sign tokens.
 *
 * <p>Key rotation: RSA key pairs should be rotated every 90 days. During rotation:
 * 1. Generate new key pair
 * 2. Update keys/ directory
 * 3. Rolling restart services (new tokens signed with new key; old tokens still validate until expiry)
 * 4. After all old tokens expire (max 15 min), remove old public key
 */
@Configuration
@Slf4j
public class JwtConfig {

  @Value("${vaultflow.jwt.access-token-expiry-seconds:900}")
  private long accessTokenExpirySeconds;

  @Value("${vaultflow.jwt.refresh-token-expiry-seconds:604800}")
  private long refreshTokenExpirySeconds;

  @Value("${vaultflow.jwt.issuer:vaultflow-auth}")
  private String issuer;

  @Value("${vaultflow.jwt.private-key-path:/keys/private.pem}")
  private String privateKeyPath;

  @Value("${vaultflow.jwt.public-key-path:/keys/public.pem}")
  private String publicKeyPath;

  @Bean
  public JwtTokenProvider jwtTokenProvider() {
    try {
      PrivateKey privateKey = PemUtil.loadPrivateKey(Path.of(privateKeyPath));
      PublicKey publicKey = PemUtil.loadPublicKey(Path.of(publicKeyPath));
      log.info("Loaded RSA key pair: private={} public={}", privateKeyPath, publicKeyPath);
      return new JwtTokenProvider(
          privateKey, publicKey,
          accessTokenExpirySeconds, refreshTokenExpirySeconds, issuer);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to load JWT RSA keys. Check that keys/private.pem and keys/public.pem exist. "
              + "Generate them with: node -e \"const c=require('crypto');const{p,t}=c.generateKeyPairSync('rsa',"
              + "{modulusLength:2048,publicKeyEncoding:{type:'spki',format:'pem'},"
              + "privateKeyEncoding:{type:'pkcs8',format:'pem'}});"
              + "require('fs').writeFileSync('keys/private.pem',t);require('fs').writeFileSync('keys/public.pem',p);\"",
          e);
    }
  }
}