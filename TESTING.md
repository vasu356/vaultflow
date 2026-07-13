# Testing Guide

VaultFlow has three levels of automated testing: unit tests, integration tests (Testcontainers), and end-to-end tests (k6 load testing). The CI/CD pipeline enforces 80% minimum line coverage.

---

## Test Structure

```
service/src/test/java/com/vaultflow/<service>/
  service/              # Unit tests for service classes
  controller/           # Unit tests for controllers (MockMvc)
  storage/              # Unit tests for storage adapters
  util/                 # Unit tests for utilities
  integration/          # Integration tests (*IntegrationTest.java)
    *IntegrationTest.java  # Testcontainers-based full-stack tests
```

---

## Running Tests

### Unit Tests (Fast — No Docker Required)

```bash
# All modules
mvn test

# Single module
cd auth-service && mvn test

# Single test class
mvn test -pl auth-service -Dtest=AuthServiceTest

# Single test method
mvn test -pl auth-service -Dtest=AuthServiceTest#login_WithValidCredentials_ReturnsTokens
```

Unit tests complete in < 60 seconds for the full codebase.

### Integration Tests (Testcontainers — Docker Required)

Integration tests are named `*IntegrationTest.java` and excluded from `mvn test`. Run with:

```bash
# All integration tests
mvn verify

# Single module integration tests
mvn verify -pl auth-service

# With Testcontainers container reuse (faster repeated runs)
export TESTCONTAINERS_RYUK_DISABLED=true
mvn verify

# Watch progress
mvn verify -pl upload-service -Dsurefire.useFile=false
```

Testcontainers automatically pulls and starts PostgreSQL, Redis, and Kafka containers for the test run. First run takes 1–2 minutes to pull images. Subsequent runs are faster due to image caching and (optionally) container reuse.

### Full Test Suite

```bash
# Quality → Unit → Integration (matches CI pipeline)
mvn spotless:check checkstyle:check test verify
```

---

## Test Coverage

Coverage is enforced by JaCoCo at **80% minimum line coverage** per module.

```bash
# Generate coverage report
mvn jacoco:report

# View HTML report (after running mvn test)
open auth-service/target/site/jacoco/index.html

# Check coverage gate (fails if < 80%)
mvn jacoco:check
```

The CI pipeline uploads coverage reports as artifacts on every run.

---

## Writing Tests

### Unit Test Conventions

```java
@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    @Mock private StoredObjectRepository objectRepository;
    @Mock private ObjectStoragePort storage;
    @Mock private QuotaService quotaService;
    @Mock private MeterRegistry meterRegistry;
    @InjectMocks private UploadService uploadService;

    // Method naming: methodName_Scenario_ExpectedBehavior
    @Test
    void uploadSinglePart_WhenQuotaExceeded_ThrowsException() {
        // Arrange
        doThrow(new QuotaExceededException("quota exceeded"))
            .when(quotaService).assertQuota(any(), anyLong());

        // Act & Assert
        assertThatThrownBy(() ->
            uploadService.uploadSinglePart(bucketId, "key.txt",
                inputStream, 1024L, "text/plain", null, principal))
            .isInstanceOf(QuotaExceededException.class);

        // Verify storage was never called (early exit)
        verifyNoInteractions(storage);
    }

    @Test
    void uploadSinglePart_WhenDuplicateContent_DoesNotConsumeQuota() {
        // Arrange
        when(storage.exists(anyString())).thenReturn(true); // duplicate
        // ...

        // Act
        UploadResponse response = uploadService.uploadSinglePart(...);

        // Assert
        assertThat(response.isDuplicate()).isTrue();
        verify(quotaService, never()).consumeQuota(any(), anyLong());
    }
}
```

### Integration Test Pattern

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AuthIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("vaultflow_test")
        .withUsername("vaultflow")
        .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Test
    void register_ThenLogin_ThenRefresh_FullTokenLifecycle() {
        // Full end-to-end flow: register org → login → get token → refresh → logout
    }
}
```

### Kafka Testing

For tests that publish or consume Kafka events, use the `spring-kafka-test` `EmbeddedKafkaBroker`:

```java
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"file.uploaded", "file.processed"})
class ProcessingOrchestratorTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void process_WhenImageUploaded_PublishesProcessedEvent() throws Exception {
        FileUploadedEvent event = /* create test event */;

        kafkaTemplate.send("file.uploaded", event.objectId(), event);

        // Use Awaitility for async assertion
        await().atMost(10, SECONDS)
            .untilAsserted(() ->
                verify(processingResultPersistenceService)
                    .persistResults(eq(event.objectVersionId()), any()));
    }
}
```

---

## Existing Test Coverage

### auth-service

| Test Class | Type | What It Tests |
|---|---|---|
| `AuthServiceTest` | Unit | Login, registration, logout, token refresh, account lockout |
| `UserServiceTest` | Unit | User creation, role updates, status changes |
| `AuthIntegrationTest` | Integration | Full register → login → refresh → logout flow with real PostgreSQL |

### upload-service

| Test Class | Type | What It Tests |
|---|---|---|
| `UploadServiceTest` | Unit | Single-part upload, deduplication, quota enforcement, checksum validation |
| `LocalFileSystemStorageTest` | Unit | File store/retrieve/delete, part storage, assembly |
| `EtagUtilTest` | Unit | ETag generation for single-part and multipart uploads |

### processing-service

| Test Class | Type | What It Tests |
|---|---|---|
| `VirusScanProcessorTest` | Unit | EICAR pattern detection, clean files, error handling |
| `ProcessingOrchestratorTest` | Unit | Parallel processor coordination, result aggregation, metric recording |

### common

| Test Class | Type | What It Tests |
|---|---|---|
| `ChecksumUtilTest` | Unit | SHA-256 computation, storage path generation, checksum verification |

---

## Load Testing

VaultFlow includes a k6 load test script for performance validation.

### Prerequisites

```bash
# Install k6
# macOS
brew install k6

# Linux
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6
```

### Running Load Tests

```bash
# Standard run (10 VUs, 1 minute)
k6 run infrastructure/load-test.js

# Custom parameters
k6 run \
  --env BASE_URL=http://localhost:80 \
  --vus 50 \
  --duration 5m \
  infrastructure/load-test.js

# Ramp test (gradual load increase)
k6 run \
  --env BASE_URL=http://localhost:80 \
  --stage 1m:10,3m:50,1m:100,2m:50,1m:0 \
  infrastructure/load-test.js
```

### Load Test Scenarios

The `infrastructure/load-test.js` script covers:
1. **Register** — creates a new organization
2. **Login** — authenticates and captures token
3. **Create bucket** — creates a test bucket
4. **Upload** — uploads a small test file
5. **Download** — downloads the uploaded file
6. **Signed URL** — generates and uses a signed URL

### Expected Thresholds

| Metric | Threshold |
|---|---|
| HTTP failure rate | < 0.1% |
| Upload p95 latency | < 500 ms |
| Download p95 TTFB | < 200 ms |
| Login p99 latency | < 300 ms |

---

## CI Test Pipeline

The GitHub Actions pipeline runs tests in this order (each stage gates the next):

```
Code Quality (Spotless + Checkstyle)
    ↓
Unit Tests (mvn test)
    ↓
Integration Tests (mvn verify with Testcontainers)
    ↓
Security Scan (Trivy + OWASP Dependency Check)
    ↓
Docker Build + Image Scan
    ↓
Deploy to Staging → Smoke Tests
    ↓
Deploy to Production → Smoke Tests
```

Test results are uploaded as GitHub Actions artifacts. Coverage reports are available for download per run.

---

## Test Data and Fixtures

Tests should be self-contained and not depend on shared state. Use the following patterns:

```java
// Generate unique identifiers to avoid collisions between parallel tests
String uniqueEmail = "test+" + UUID.randomUUID() + "@example.com";
String uniqueSlug = "test-org-" + UUID.randomUUID().toString().substring(0, 8);

// Use @BeforeEach to set up and @AfterEach to clean up
@AfterEach
void cleanup() {
    // Clean up test data from the shared Testcontainer database
    testEntityManager.flush();
    // Or use @Transactional on the test class for automatic rollback
}
```

Use `@Transactional` on integration test classes for automatic rollback after each test. Be careful with Kafka tests — messages are not rolled back and may interfere with consumer assertions in other tests.
