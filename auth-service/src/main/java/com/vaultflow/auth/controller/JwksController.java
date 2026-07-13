package com.vaultflow.auth.controller;

import com.vaultflow.common.security.JwtTokenProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JWKS endpoint — exposes the RSA public key so other services can validate JWTs without sharing
 * the private key.
 *
 * <p>Other services configure: spring.security.oauth2.resourceserver.jwt.jwk-set-uri:
 * http://auth-service:8081/.well-known/jwks.json
 *
 * <p>This is the production solution to the shared-key problem. Each service fetches the public key
 * on startup (Spring caches it). Tokens signed by auth-service's private key will validate
 * correctly in all downstream services.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "JWKS", description = "JSON Web Key Set for distributed JWT validation")
public class JwksController {

  private final JwtTokenProvider jwtTokenProvider;

  @GetMapping("/.well-known/jwks.json")
  @Operation(summary = "RSA public key set for JWT signature validation")
  public Map<String, Object> getJwks() {
    if (!(jwtTokenProvider.getPublicKey() instanceof RSAPublicKey rsaKey)) {
      return Map.of("keys", List.of());
    }

    return Map.of(
        "keys",
        List.of(
            Map.of(
                "kty", "RSA",
                "use", "sig",
                "alg", "RS256",
                "kid", "vaultflow-auth-key-1",
                "n",
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(rsaKey.getModulus().toByteArray()),
                "e",
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(rsaKey.getPublicExponent().toByteArray()))));
  }
}
