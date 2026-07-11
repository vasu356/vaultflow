package com.vaultflow.auth.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import com.vaultflow.auth.AuthServiceApplication;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    classes = AuthServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Auth Service Integration Tests")
class AuthIntegrationTest {

  @TempDir static Path tempKeyDir;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("vaultflow_test")
          .withUsername("vaultflow")
          .withPassword("vaultflow_test_pw");

  @SuppressWarnings("rawtypes")
  @Container
  static GenericContainer redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          // Wait until Redis is actually accepting connections before tests start
          .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

  @LocalServerPort int port;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) throws Exception {
    // Generate an in-memory RSA key pair for tests — no file dependency
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
    kpg.initialize(2048);
    KeyPair keyPair = kpg.generateKeyPair();

    // Write PEM files to temp directory
    Path privateKeyPath = tempKeyDir.resolve("private.pem");
    Path publicKeyPath = tempKeyDir.resolve("public.pem");

    String privateKeyPem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(keyPair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    String publicKeyPem =
        "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(keyPair.getPublic().getEncoded())
            + "\n-----END PUBLIC KEY-----\n";

    Files.writeString(privateKeyPath, privateKeyPem);
    Files.writeString(publicKeyPath, publicKeyPem);

    // Database — Testcontainers manages the actual connection details
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);

    // Redis
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    registry.add("spring.data.redis.password", () -> ""); // no password in test Redis

    // JWT keys — generated dynamically above
    registry.add("vaultflow.jwt.private-key-path", privateKeyPath::toString);
    registry.add("vaultflow.jwt.public-key-path", publicKeyPath::toString);

    // Kafka disabled for integration tests (auth-service doesn't produce to Kafka)
    registry.add(
        "spring.autoconfigure.exclude",
        () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
  }

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    RestAssured.basePath = "";
  }

  // Shared state across ordered test methods (simulates a real session)
  private static String accessToken;
  private static String refreshToken;

  @Test
  @Order(1)
  @DisplayName("POST /api/v1/auth/register - registers org and owner, returns tokens")
  void registerOrganization() {
    var body =
        """
        {
          "organizationName": "Test Corp",
          "organizationSlug": "test-corp",
          "fullName": "Test Owner",
          "email": "owner@testcorp.com",
          "password": "SecurePass1!"
        }
        """;

    var response =
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post("/api/v1/auth/register")
            .then()
            .statusCode(201)
            .body("accessToken", notNullValue())
            .body("refreshToken", notNullValue())
            .body("tokenType", equalTo("Bearer"))
            .body("user.email", equalTo("owner@testcorp.com"))
            .body("user.role", equalTo("OWNER"))
            .body("user.orgSlug", equalTo("test-corp"))
            .extract()
            .response();

    accessToken = response.jsonPath().getString("accessToken");
    refreshToken = response.jsonPath().getString("refreshToken");

    assertThat(accessToken).isNotBlank();
    assertThat(refreshToken).isNotBlank();
  }

  @Test
  @Order(2)
  @DisplayName("POST /api/v1/auth/register - duplicate slug returns 409")
  void duplicateSlugReturns409() {
    var body =
        """
        {
          "organizationName": "Test Corp 2",
          "organizationSlug": "test-corp",
          "fullName": "Another Owner",
          "email": "owner2@testcorp.com",
          "password": "SecurePass1!"
        }
        """;

    given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/auth/register")
        .then()
        .statusCode(409)
        .body("errorCode", equalTo("CONFLICT"));
  }

  @Test
  @Order(3)
  @DisplayName("POST /api/v1/auth/login - valid credentials return tokens")
  void loginWithValidCredentials() {
    var body =
        """
        {
          "email": "owner@testcorp.com",
          "password": "SecurePass1!"
        }
        """;

    given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/auth/login")
        .then()
        .statusCode(200)
        .body("accessToken", notNullValue())
        .body("user.email", equalTo("owner@testcorp.com"));
  }

  @Test
  @Order(4)
  @DisplayName("POST /api/v1/auth/login - wrong password returns 401")
  void loginWithWrongPassword() {
    var body =
        """
        {
          "email": "owner@testcorp.com",
          "password": "WrongPassword1!"
        }
        """;

    given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/auth/login")
        .then()
        .statusCode(401)
        .body("errorCode", equalTo("INVALID_CREDENTIALS"));
  }

  @Test
  @Order(5)
  @DisplayName("GET /api/v1/auth/me - returns current user with valid token")
  void getMeWithValidToken() {
    given()
        .header("Authorization", "Bearer " + accessToken)
        .when()
        .get("/api/v1/auth/me")
        .then()
        .statusCode(200);
  }

  @Test
  @Order(6)
  @DisplayName("GET /api/v1/auth/me - returns 401 without token")
  void getMeWithoutToken() {
    given().when().get("/api/v1/auth/me").then().statusCode(401);
  }

  @Test
  @Order(7)
  @DisplayName("POST /api/v1/auth/refresh - exchanges refresh token for new pair")
  void refreshTokenSuccess() {
    var body = String.format("{\"refreshToken\": \"%s\"}", refreshToken);

    var response =
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post("/api/v1/auth/refresh")
            .then()
            .statusCode(200)
            .body("accessToken", notNullValue())
            .body("refreshToken", notNullValue())
            .extract()
            .response();

    // Update tokens for subsequent tests (token rotation)
    accessToken = response.jsonPath().getString("accessToken");
    refreshToken = response.jsonPath().getString("refreshToken");
  }

  @Test
  @Order(8)
  @DisplayName(
      "POST /api/v1/auth/refresh - reusing previous refresh token returns 401 (theft detection)")
  void refreshTokenReuse_DetectsTheft() {
    // The refresh token from @Order(1) was already rotated in @Order(7)
    // Attempting to use the original token should trigger family revocation
    var originalToken = refreshToken; // This is now the rotated token from Order(7)

    // First use the current token normally
    var body = String.format("{\"refreshToken\": \"%s\"}", originalToken);
    var newRefresh =
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post("/api/v1/auth/refresh")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("refreshToken");

    // Now try to reuse the old token (should be rejected)
    given()
        .contentType(ContentType.JSON)
        .body(body) // same old token
        .when()
        .post("/api/v1/auth/refresh")
        .then()
        .statusCode(401)
        .body("errorCode", equalTo("TOKEN_REUSE"));
  }

  @Test
  @Order(9)
  @DisplayName("POST /api/v1/auth/register - weak password returns 400 with field errors")
  void registrationWithWeakPassword() {
    var body =
        """
        {
          "organizationName": "New Corp",
          "organizationSlug": "new-corp-unique",
          "fullName": "New Owner",
          "email": "owner@newcorp.com",
          "password": "weak"
        }
        """;

    given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/auth/register")
        .then()
        .statusCode(400)
        .body("errorCode", equalTo("VALIDATION_FAILED"))
        .body("fieldErrors", hasSize(greaterThan(0)));
  }
}
