# Contributing to VaultFlow

Thank you for your interest in contributing. This document covers the contribution workflow, coding standards, and review process.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Workflow](#development-workflow)
- [Coding Standards](#coding-standards)
- [Testing Requirements](#testing-requirements)
- [Pull Request Process](#pull-request-process)
- [Issue Guidelines](#issue-guidelines)
- [Architecture Decisions](#architecture-decisions)
- [Release Process](#release-process)

---

## Code of Conduct

This project adheres to our [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold it. Please report unacceptable behavior to **conduct@vaultflow.io**.

---

## Getting Started

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Docker Desktop | 24+ | Includes Compose v2 |
| Java | 21 (Temurin recommended) | `sdk install java 21.0.3-tem` |
| Maven | 3.9+ | `sdk install maven` |
| Git | 2.40+ | — |

### Fork and Clone

```bash
# Fork on GitHub, then:
git clone https://github.com/YOUR_USERNAME/vaultflow.git
cd vaultflow

# Add upstream remote
git remote add upstream https://github.com/your-org/vaultflow.git
```

### First Build

```bash
# Build all modules (skipping tests for speed)
mvn clean package -DskipTests

# Run unit tests
mvn test

# Start infrastructure for integration tests
docker-compose up -d postgres redis zookeeper kafka kafka-init

# Run integration tests
mvn verify
```

If the build fails, check [docs/LOCAL_DEVELOPMENT.md](docs/LOCAL_DEVELOPMENT.md) for troubleshooting.

---

## Development Workflow

### Branch Naming

```
feature/<short-description>      # New feature
fix/<short-description>          # Bug fix
docs/<short-description>         # Documentation only
refactor/<short-description>     # Refactoring (no behavior change)
test/<short-description>         # Test additions
chore/<short-description>        # Build, CI, dependency updates
```

Examples:
- `feature/s3-storage-adapter`
- `fix/multipart-completion-race-condition`
- `docs/kubernetes-deployment-guide`

### Keeping Your Branch Updated

```bash
git fetch upstream
git rebase upstream/main
```

Prefer rebase over merge to keep a clean history.

---

## Coding Standards

### Java Style

VaultFlow uses **Google Java Format** enforced by Spotless. Before committing:

```bash
# Check formatting
mvn spotless:check

# Auto-apply formatting
mvn spotless:apply
```

Your IDE can apply Google Java Format automatically:
- **IntelliJ IDEA**: Install `google-java-format` plugin, enable "Reformat code" on save
- **VS Code**: Install `Language Support for Java` + configure formatter

### Key Conventions

**Package structure** (consistent across all services):

```
com.vaultflow.<service>/
  config/           # Spring configuration beans
  controller/       # REST controllers (thin — delegate to service)
  domain/
    entity/         # JPA entities
    enums/          # Domain enumerations
    repository/     # Spring Data repositories
  dto/
    request/        # Incoming request DTOs (Java records preferred)
    response/       # Outgoing response DTOs (Java records preferred)
  service/          # Business logic
  exception/        # Service-specific exceptions (extend common hierarchy)
```

**DTO preference**: Use Java records for DTOs. They are immutable by default and require less boilerplate.

```java
// Preferred
public record UploadRequest(UUID bucketId, String objectKey, String contentType) {}

// Avoid
@Data
public class UploadRequest {
    private UUID bucketId;
    private String objectKey;
    private String contentType;
}
```

**Exception handling**: Throw exceptions from the `common` module's hierarchy. Do not return `ResponseEntity` with error codes from service classes — let `GlobalExceptionHandler` handle it.

```java
// Correct
throw new ResourceNotFoundException("Bucket", bucketId.toString());
throw new QuotaExceededException("Organization quota exceeded");

// Incorrect
return ResponseEntity.status(404).body("Bucket not found");
```

**Logging**: Use structured logging. Never log secrets, tokens, passwords, or PII. Include correlation context.

```java
// Correct
log.info("Upload complete: objectId={} versionId={} size={} dedup={}", 
    object.getId(), version.getId(), fileBytes.length, isDuplicate);

// Incorrect
log.info("Uploaded file for user " + user.getEmail() + " with token " + token);
```

**Transactions**: Annotate service methods with `@Transactional`. Read-only methods should use `@Transactional(readOnly = true)` to allow database replicas and optimize connection usage.

**Virtual threads**: Do not use `synchronized` blocks in code that runs on virtual threads — use `ReentrantLock` instead. Avoid blocking the carrier thread unnecessarily (prefer `CompletableFuture` composition over sequential blocking calls when work is independent).

### SQL and Migrations

- All schema changes must be Flyway migrations in the relevant service's `src/main/resources/db/migration/`
- Migrations must be backward-compatible with the previous version (allow rolling deployments)
- Never modify an existing migration — create a new versioned file
- Add SQL comments explaining non-obvious decisions

```sql
-- V4__add_processing_status_index.sql
-- Index to speed up polling queries from metadata-service
-- Processing status is queried frequently during the pipeline window (< 30s after upload)
CREATE INDEX CONCURRENTLY idx_object_versions_processing_status
    ON object_versions (processing_status)
    WHERE processing_status IN ('PENDING', 'PROCESSING');
```

### Kafka Events

Event schemas live in `common/src/main/java/com/vaultflow/common/event/`. When modifying event schemas:

1. Increment `CURRENT_SCHEMA_VERSION`
2. Ensure consumers handle both the old and new `schemaVersion` during the transition window
3. Document the schema change in the event class Javadoc
4. Never remove fields from existing events — mark them `@Deprecated` and add new fields

---

## Testing Requirements

### Test Coverage

- **Minimum 80% line coverage** enforced by JaCoCo in CI. New code must maintain or improve coverage.
- **Unit tests** go in `src/test/java` and use `@ExtendWith(MockitoExtension.class)` for fast, isolated tests.
- **Integration tests** are named `*IT.java` or `*IntegrationTest.java` and use Testcontainers for real infrastructure.

### Test Conventions

```java
// Unit test naming: methodUnderTest_Scenario_ExpectedBehavior
@Test
void uploadSinglePart_WhenQuotaExceeded_ThrowsQuotaExceededException() { ... }

@Test
void uploadSinglePart_WhenChecksumMismatch_ThrowsInvalidUploadException() { ... }

@Test
void uploadSinglePart_WhenDuplicateContent_ReturnsDuplicateFlag() { ... }
```

### What Must Be Tested

| Category | Requirement |
|---|---|
| Business logic | Unit test all happy path and error paths |
| Quota enforcement | Unit test boundary conditions |
| Checksum verification | Unit test mismatch detection |
| Kafka publishing | Verify event published with correct fields |
| Database queries | Integration test with Testcontainers PostgreSQL |
| Content-type detection | Unit test with mock Tika |
| Security config | Integration test that unauthenticated requests are rejected |

### Running Tests

```bash
# Unit tests (fast, no Docker)
mvn test

# Integration tests (requires Docker)
mvn verify

# Single module
cd upload-service && mvn test

# With Testcontainers container reuse (faster repeated runs)
export TESTCONTAINERS_RYUK_DISABLED=true
mvn verify -pl auth-service

# Coverage report
mvn jacoco:report
open target/site/jacoco/index.html
```

---

## Pull Request Process

### Before Opening a PR

- [ ] `mvn spotless:check` passes
- [ ] `mvn checkstyle:check` passes
- [ ] `mvn test` passes
- [ ] `mvn verify` passes (integration tests)
- [ ] New code has ≥ 80% test coverage
- [ ] Javadoc added for public methods in service classes
- [ ] No sensitive data, tokens, or passwords in code or tests

### PR Title Format

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add S3-compatible storage adapter for ObjectStoragePort
fix: resolve race condition in multipart upload completion
docs: add Kubernetes deployment guide for GKE
refactor: extract quota validation into dedicated QuotaService
test: add integration tests for token rotation family invalidation
chore: upgrade Spring Boot to 3.3.0
```

### PR Description

Use the [Pull Request template](.github/PULL_REQUEST_TEMPLATE/pull_request_template.md). Fill in all sections:

- **What** — what changes were made
- **Why** — motivation and context
- **How** — key implementation decisions
- **Testing** — how you tested it
- **Checklist** — all items checked

### Review Process

1. **Automated checks** must pass (CI pipeline)
2. **One approving review** from a maintainer required for merging
3. **Resolving requested changes** — address or discuss every comment before re-requesting review
4. **Squash and merge** is the default merge strategy — keep the commit history clean

### Review Turnaround

Maintainers aim to review PRs within **3 business days**. For urgent security fixes, tag the PR with `security` and ping `@vaultflow/maintainers`.

---

## Issue Guidelines

### Before Opening an Issue

1. Search existing issues (open and closed)
2. Check [FAQ.md](FAQ.md) for common questions
3. Check [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for error resolution

### Bug Reports

Use the **Bug Report** issue template. Include:
- VaultFlow version / commit SHA
- Java version, OS
- Minimal reproduction steps
- Actual vs expected behavior
- Relevant logs (redact any sensitive data)

### Feature Requests

Use the **Feature Request** issue template. Describe:
- The problem you are trying to solve
- Your proposed solution
- Alternatives you have considered
- Whether you are willing to implement it

### Security Issues

**Do not open public issues for security vulnerabilities.** See [SECURITY.md](SECURITY.md) for the responsible disclosure process.

---

## Architecture Decisions

Significant design changes (new services, changes to Kafka schemas, new external dependencies, changes to authentication model) require an Engineering Decision Record (EDR).

```markdown
## EDR-NNN: Brief Title

**Date:** YYYY-MM  
**Status:** Proposed | Accepted | Deprecated  
**Author:** Your Name

### Problem
[What problem are you solving?]

### Decision
[What did you decide?]

### Alternatives Considered
- **Option A**: [description]. Rejected because [reason].
- **Option B**: [description]. Rejected because [reason].

### Trade-offs
- ✅ [Benefit]
- ⚠️ [Cost or limitation]
```

Add the EDR to `docs/ENGINEERING_DECISION_RECORDS.md` and reference it in your PR.

---

## Release Process

VaultFlow follows [Semantic Versioning](https://semver.org/):

- **MAJOR** (`1.0.0` → `2.0.0`): Breaking API changes
- **MINOR** (`1.0.0` → `1.1.0`): Backward-compatible new features
- **PATCH** (`1.0.0` → `1.0.1`): Backward-compatible bug fixes

Releases are created by maintainers. Contributors do not need to manage versioning. See [CHANGELOG.md](CHANGELOG.md) for release history.

---

## Questions?

- **GitHub Discussions**: Use [Discussions](https://github.com/your-org/vaultflow/discussions) for questions, ideas, and community conversation
- **Bug reports**: Open a [GitHub Issue](https://github.com/your-org/vaultflow/issues/new/choose)
- **Security issues**: Email security@vaultflow.io

We appreciate every contribution, from typo fixes to major features. Thank you.
