package com.vaultflow.auth.integration;

import static io.restassured.RestAssured.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;

import com.vaultflow.auth.AuthServiceApplication;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    classes = AuthServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Auth Service Integration Tests")
class AuthIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("vaultflow_test")
          .withUsername("vaultflow")
          .withPassword("vaultflow");

  @SuppressWarnings("rawtypes")
  @Container
  static GenericContainer redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @LocalServerPort
  int port;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9999"); // disabled in tests
    registry.add("spring.autoconfigure.exclude",
        () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
  }

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    RestAssured.basePath = "";
  }

  private static String accessToken;
  private static String refreshToken;

  @Test
  @Order(1)
  @DisplayName("POST /api/v1/auth/register - registers org and owner, returns tokens")
  void registerOrganization() {
    var body = """
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
    var body = """
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
    var body = """
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
    var body = """
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
    given()
        .when()
        .get("/api/v1/auth/me")
        .then()
        .statusCode(401);
  }

  @Test
  @Order(7)
  @DisplayName("POST /api/v1/auth/refresh - exchanges refresh token for new pair")
  void refreshTokenSuccess() {
    var body = String.format("{\"refreshToken\": \"%s\"}", refreshToken);

    given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/auth/refresh")
        .then()
        .statusCode(200)
        .body("accessToken", notNullValue())
        .body("refreshToken", notNullValue());
  }

  @Test
  @Order(8)
  @DisplayName("POST /api/v1/auth/register - invalid password returns 400 with field errors")
  void registrationWithWeakPassword() {
    var body = """
        {
          "organizationName": "New Corp",
          "organizationSlug": "new-corp",
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
