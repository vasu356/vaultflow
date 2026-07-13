<div align="center">

# VaultFlow

**Enterprise Object Storage & File Processing Platform**

[![CI/CD](https://github.com/your-org/vaultflow/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/your-org/vaultflow/actions/workflows/ci-cd.yml)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.2](https://img.shields.io/badge/Spring%20Boot-3.2-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Apache Kafka](https://img.shields.io/badge/Apache%20Kafka-3.7-231F20?logo=apachekafka)](https://kafka.apache.org/)
[![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-316192?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis 7](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)](https://redis.io/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Production-grade, horizontally scalable object storage combining S3-compatible file management, asynchronous processing pipelines, multi-tenant RBAC, content deduplication, and full observability — built with Java 21 Virtual Threads and a microservices architecture.

[Quick Start](#quick-start) · [Architecture](#architecture) · [API Reference](#api-reference) · [Configuration](#configuration-reference) · [Contributing](CONTRIBUTING.md) · [Docs](docs/)

</div>

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Services](#services)
- [Tech Stack](#tech-stack)
- [Quick Start](#quick-start)
- [Local Development](#local-development)
- [API Reference](#api-reference)
- [Configuration Reference](#configuration-reference)
- [Security Model](#security-model)
- [Observability](#observability)
- [Kubernetes Deployment](#kubernetes-deployment)
- [Load Testing](#load-testing)
- [Project Structure](#project-structure)
- [Design Decisions](#design-decisions)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

VaultFlow solves the hard parts of enterprise file storage:

| Problem | VaultFlow Solution |
|---|---|
| Large file uploads time out or fail | Resumable multipart upload with Redis-backed session state |
| Duplicate files waste storage | SHA-256 content-addressed deduplication — identical files share one physical copy |
| File processing blocks upload latency | Async Kafka pipeline — upload returns immediately, processing runs in background |
| Single-tenant ACLs are too coarse | Multi-tenant RBAC: `OWNER > ADMIN > EDITOR > VIEWER` scoped per organization |
| JWT compromise in one service compromises all | RS256 asymmetric signing — only auth-service holds the private key |
| Audit trails degrade query performance at scale | Monthly-partitioned `audit_logs` table with `DETACH PARTITION` archival |
| Storage vendor lock-in | Hexagonal storage port — swap `LocalFileSystemStorage` for S3/GCS/MinIO without touching business logic |

### Key Capabilities

- **Single-part and multipart uploads** up to 5 GB per object, 500 MB per part
- **Content deduplication** via SHA-256 content addressing with reference counting
- **Async processing pipeline**: image thumbnails (Thumbnailator), video thumbnails (FFmpeg), PDF previews (PDFBox), virus scanning (ClamAV-compatible), metadata extraction (Apache Tika)
- **Signed URLs** with HMAC-SHA256, TTL, optional IP restriction, and download count limits
- **HTTP Range requests** for partial content delivery and resumable downloads
- **Multi-tenant RBAC** with organization-scoped quotas and soft-delete with restore
- **Full observability**: Micrometer metrics → Prometheus → Grafana, OpenTelemetry traces → Jaeger
- **Java 21 Virtual Threads** — tens of thousands of concurrent uploads/downloads without reactive programming complexity
- **Kubernetes-ready** with HPA, PDB, rolling update manifests, and a 7-stage CI/CD pipeline

---

## Architecture

```
                         ┌─────────────────────────────────────────┐
                         │            NGINX API Gateway             │
                         │   TLS · Rate Limiting · Streaming I/O   │
                         └────┬──────┬──────┬──────┬──────┬────────┘
                              │      │      │      │      │
                    ┌─────────▼─┐ ┌──▼───┐ ┌▼─────┴┐ ┌───▼────┐ ┌──▼─────┐
                    │   Auth    │ │Upload│ │Download│ │Metadata│ │ Admin  │
                    │  Service  │ │ Svc  │ │  Svc   │ │  Svc   │ │  Svc   │
                    │   :8081   │ │:8082 │ │  :8083 │ │  :8084 │ │  :8087 │
                    └─────┬─────┘ └──┬───┘ └───┬────┘ └───┬────┘ └──┬─────┘
                          │          │          │           │         │
              ┌───────────┴──────────┴──────────┴───────────┴─────────┘
              │
    ┌─────────▼──────────┐     ┌─────────────────────┐
    │    PostgreSQL 16    │     │      Redis 7         │
    │  (Primary Datastore)│     │  Sessions · Cache    │
    │  Flyway Migrations  │     │  Rate Limiting       │
    │  Partitioned Audit  │     │  Token Blacklist     │
    └────────────────────┘     └─────────────────────┘
              │
    ┌─────────▼──────────────────────────────────────────────┐
    │                    Apache Kafka 3.7                     │
    │                                                         │
    │  file.uploaded (16p)  ·  file.processed (16p)          │
    │  file.processing.image · video · document · virus       │
    │  audit.events (8p)  ·  notification.events (8p)        │
    │  *.DLT (dead letter topics for all above)               │
    └────────────┬──────────────────────────┬────────────────┘
                 │                          │
    ┌────────────▼────────────┐  ┌──────────▼──────────────┐
    │   Processing Service    │  │  Notification Service    │
    │         :8085           │  │        :8086             │
    │                         │  │                          │
    │  ┌─────────────────┐    │  │  Audit log persistence   │
    │  │ Virtual Thread  │    │  │  Webhook delivery        │
    │  │    Executor     │    │  │  Retry with exponential  │
    │  └────────┬────────┘    │  │  backoff                 │
    │           │ parallel    │  └──────────────────────────┘
    │  ┌────────┼────────┐    │
    │  ▼        ▼        ▼    │
    │ Virus  Thumb  Metadata  │
    │ Scan   nail   Extract   │
    └─────────────────────────┘
```

### Upload Request Flow

```
Client ──PUT /api/v1/buckets/{id}/objects/{key}──► NGINX
  │                                                   │
  │                              proxy_request_buffering off
  │                              (streams directly, no NGINX buffer)
  │                                                   │
  │                                         ┌─────────▼──────────┐
  │                                         │   Upload Service   │
  │                                         │                    │
  │                                         │ 1. QuotaService    │
  │                                         │    assertQuota()   │
  │                                         │                    │
  │                                         │ 2. Apache Tika     │
  │                                         │    content-type    │
  │                                         │    detection       │
  │                                         │                    │
  │                                         │ 3. SHA-256 checksum│
  │                                         │    + dedup check   │
  │                                         │                    │
  │                                         │ 4. LocalFS / S3    │
  │                                         │    store (if new)  │
  │                                         │                    │
  │                                         │ 5. DB: StoredObject│
  │                                         │    ObjectVersion   │
  │                                         │    (versioned)     │
  │                                         │                    │
  │                                         │ 6. Kafka publish   │
  │                                         │    FileUploadedEvent│
  │                                         └────────────────────┘
  │                                                   │
  │◄────────────── HTTP 200 (immediate) ──────────────┘
  │                                                   │
  │                                    ┌──────────────▼───────────────┐
  │                                    │    Processing Service        │
  │                                    │  (consumes FileUploadedEvent)│
  │                                    │                              │
  │                                    │  Parallel virtual threads:   │
  │                                    │  ┌──────────┬────────────┐  │
  │                                    │  │VirusScan │ Thumbnail  │  │
  │                                    │  └──────────┴────────────┘  │
  │                                    │  ┌──────────┬────────────┐  │
  │                                    │  │  Metadata│ PDF Preview│  │
  │                                    │  └──────────┴────────────┘  │
  │                                    └──────────────────────────────┘
```

### Multipart Upload Flow

```
Client                    NGINX              Upload Service         PostgreSQL    Redis
  │                         │                      │                    │           │
  ├─ POST /uploads/initiate ─►                     │                    │           │
  │                         ├─────────────────────►│                    │           │
  │                         │                      ├── INSERT session ──►           │
  │                         │                      │◄── sessionId ──────┘           │
  │◄──── { sessionId } ──────────────────────────◄┤                                │
  │                         │                      │                                │
  ├─ PUT /uploads/{id}/parts/1 ──────────────────►│                                │
  ├─ PUT /uploads/{id}/parts/2 ──────────────────►│  (parallel, any order)         │
  ├─ PUT /uploads/{id}/parts/3 ──────────────────►│                                │
  │                         │                      ├── store parts ─────────────────►
  │                         │                      ├── INSERT UploadPart ──►        │
  │                         │                      ├── session.status = UPLOADING ──►
  │                         │                      │                                │
  ├─ POST /uploads/{id}/complete ────────────────►│                                │
  │                         │                      ├── Redis SETNX lock ───────────►│
  │                         │                      ├── assemble parts               │
  │                         │                      ├── compute multipart checksum   │
  │                         │                      ├── INSERT ObjectVersion ──►     │
  │                         │                      ├── publish FileUploadedEvent    │
  │◄──── { objectId, versionId, etag } ──────────◄┤                                │
```

---

## Services

| Service | Port | Responsibilities |
|---|---|---|
| **auth-service** | 8081 | JWT issuance (RS256), refresh token rotation with family theft-detection, RBAC, organization registration, JWKS endpoint |
| **upload-service** | 8082 | Single-part upload, resumable multipart upload, content deduplication (SHA-256), quota enforcement, bucket management |
| **download-service** | 8083 | Streaming file download, HTTP Range requests, HMAC-SHA256 signed URLs with TTL and IP restriction |
| **metadata-service** | 8084 | File metadata search, lifecycle scheduling, processing status queries |
| **processing-service** | 8085 | Async Kafka consumer: image thumbnails, video thumbnails, PDF previews, virus scanning, metadata extraction — all processors run in parallel via Virtual Thread executor |
| **notification-service** | 8086 | Audit log persistence, webhook delivery with exponential backoff retry |
| **admin-service** | 8087 | Usage analytics, organization quota management, audit log API, platform health overview |
| **common** | — | Shared library: JWT validation, event schemas, exception hierarchy, correlation ID filter, checksum utilities |

---

## Tech Stack

| Layer | Technology | Version | Rationale |
|---|---|---|---|
| Language | Java + Virtual Threads | 21 | Near-unlimited I/O concurrency without reactive programming complexity |
| Framework | Spring Boot | 3.2 | Industry standard; Testcontainers integration; Virtual Thread support |
| Database | PostgreSQL | 16 | JSONB, declarative partitioning, `pg_trgm` for full-text search |
| Cache & Sessions | Redis | 7 | Upload session state, token blacklist, rate limiting, distributed locks |
| Messaging | Apache Kafka | 3.7 | Event replay, Dead Letter Topics, independent service scaling |
| Gateway | NGINX | 1.25 | Zero-copy streaming, native rate limiting, no JVM overhead |
| Content Detection | Apache Tika | 2.9.2 | Magic-byte content-type detection (client `Content-Type` is untrusted) |
| Image Processing | Thumbnailator | 0.4.20 | High-quality JPEG/PNG thumbnail generation |
| PDF Processing | Apache PDFBox | 3.0.2 | PDF first-page preview rendering |
| JWT | JJWT | 0.12.5 | RS256 signing/validation, JWKS support |
| Rate Limiting | Bucket4j + Redis | 8.10 | Sliding window rate limiting backed by Redis |
| Resilience | Resilience4j | 2.2 | Circuit breaker, retry, timeout for external calls |
| Metrics | Micrometer + Prometheus | 1.12 | JVM, HTTP, Kafka, Hikari, custom business metrics |
| Tracing | OpenTelemetry + Jaeger | 1.38 | Distributed trace propagation across services |
| Migrations | Flyway | 10.12 | Versioned SQL migrations, applied on startup |
| CI/CD | GitHub Actions | — | 7-stage pipeline: quality → test → security → build → scan → deploy |
| Container | Docker multi-stage | — | Non-root user, minimal JRE layer, BuildKit layer caching |
| Orchestration | Kubernetes | — | HPA, PDB, rolling updates, namespace isolation |

---

## Quick Start

### Prerequisites

| Tool | Minimum Version | Notes |
|---|---|---|
| Docker Desktop | 24.0 | Includes Docker Compose v2 |
| Java | 21 | Required only for local JVM development |
| Maven | 3.9 | Required only for local JVM development |

On Ubuntu/WSL, install all prerequisites automatically:

```bash
chmod +x install.sh && ./install.sh
```

### Start with Docker Compose

```bash
git clone https://github.com/your-org/vaultflow.git
cd vaultflow

# Generate RSA key pair for JWT (required on first run)
mkdir -p keys
openssl genrsa -out keys/private.pem 2048
openssl rsa -in keys/private.pem -pubout -out keys/public.pem

# Build and start all services (~3-5 minutes on first run)
docker-compose up -d

# Watch startup progress
docker-compose logs -f auth-service upload-service

# Verify all services are healthy
docker-compose ps
```

All services are ready when `docker-compose ps` shows `(healthy)` for every container.

### Verify and Test

```bash
# 1. Register an organization
RESPONSE=$(curl -s -X POST http://localhost:80/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "organizationName": "Acme Corp",
    "organizationSlug": "acme-corp",
    "fullName": "Alice Admin",
    "email": "alice@acme.com",
    "password": "Password1!"
  }')

export TOKEN=$(echo $RESPONSE | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# 2. Create a bucket
BUCKET=$(curl -s -X POST http://localhost:80/api/v1/buckets \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "my-bucket"}')

export BUCKET_ID=$(echo $BUCKET | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

# 3. Upload a file
echo "Hello, VaultFlow!" > /tmp/test.txt
curl -s -X PUT "http://localhost:80/api/v1/buckets/$BUCKET_ID/objects/hello/world.txt" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: text/plain" \
  --data-binary @/tmp/test.txt | python3 -m json.tool

# 4. Download it back
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:80/api/v1/buckets/$BUCKET_ID/objects/hello/world.txt"
```

### Observability UIs

| Service | URL | Credentials |
|---|---|---|
| API Gateway | http://localhost:80 | — |
| Auth Service Swagger | http://localhost:8081/swagger-ui.html | — |
| Grafana | http://localhost:3001 | admin / admin |
| Prometheus | http://localhost:9091 | — |
| Jaeger Tracing | http://localhost:16686 | — |

---

## Local Development

For detailed local development setup including JWT key sharing, running individual services, and debugging, see **[docs/LOCAL_DEVELOPMENT.md](docs/LOCAL_DEVELOPMENT.md)**.

```bash
# Start only infrastructure (Postgres, Redis, Kafka)
docker-compose up -d postgres redis zookeeper kafka kafka-init

# Run a single service from source (faster iteration)
cd auth-service
mvn spring-boot:run

# Run all tests
mvn test

# Run integration tests (requires Docker for Testcontainers)
mvn verify

# Check code formatting
mvn spotless:check

# Apply formatting
mvn spotless:apply
```

---

## Multipart Upload (Large Files)

For files larger than 100 MB, use the multipart upload API:

```bash
# 1. Initiate — creates a resumable session (valid 24 hours)
SESSION_ID=$(curl -s -X POST http://localhost:80/api/v1/uploads/initiate \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"bucketId\": \"$BUCKET_ID\",
    \"objectKey\": \"videos/demo.mp4\",
    \"contentType\": \"video/mp4\",
    \"totalParts\": 3
  }" | python3 -c "import sys,json; print(json.load(sys.stdin)['sessionId'])")

# 2. Upload parts (can be parallel, any order, minimum 5 MB each except last)
curl -X PUT "http://localhost:80/api/v1/uploads/$SESSION_ID/parts/1" \
  -H "Authorization: Bearer $TOKEN" \
  --data-binary @part1.bin

curl -X PUT "http://localhost:80/api/v1/uploads/$SESSION_ID/parts/2" \
  -H "Authorization: Bearer $TOKEN" \
  --data-binary @part2.bin

# 3. Check which parts were received (for resume after interruption)
curl -s "http://localhost:80/api/v1/uploads/$SESSION_ID/status" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
# { "receivedPartNumbers": [1, 2], "receivedParts": 2, ... }

# 4. Complete — assembles parts and triggers processing pipeline
curl -s -X POST "http://localhost:80/api/v1/uploads/$SESSION_ID/complete" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"partNumbers": [1, 2, 3]}' | python3 -m json.tool

# 5. Abort (cleanup if abandoned)
curl -X DELETE "http://localhost:80/api/v1/uploads/$SESSION_ID" \
  -H "Authorization: Bearer $TOKEN"
```

## Signed URLs

Generate time-limited, unauthenticated download URLs:

```bash
# Generate a signed URL (7-day expiry)
curl -s -X POST http://localhost:80/api/v1/download/signed-urls \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "objectVersionId": "your-version-uuid",
    "ttlSeconds": 604800,
    "maxDownloads": 10,
    "allowedIp": "203.0.113.0/24"
  }' | python3 -m json.tool

# Download using the signed URL (no Authorization header needed)
curl -O "http://localhost:80/api/v1/download/signed?token=<signed-token>"

# HTTP Range request (resume or partial download)
curl -H "Range: bytes=0-1048575" \
  -H "Authorization: Bearer $TOKEN" \
  "http://localhost:80/api/v1/buckets/$BUCKET_ID/objects/videos/demo.mp4"
```

---

## API Reference

Full interactive OpenAPI documentation is available at each service's Swagger UI:

| Service | Swagger URL |
|---|---|
| Auth Service | http://localhost:8081/swagger-ui.html |
| Upload Service | http://localhost:8082/swagger-ui.html |
| Download Service | http://localhost:8083/swagger-ui.html |
| Admin Service | http://localhost:8087/swagger-ui.html |

### Core Endpoints

#### Authentication

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/auth/register` | Register organization and admin user |
| `POST` | `/api/v1/auth/login` | Obtain access + refresh tokens |
| `POST` | `/api/v1/auth/refresh` | Rotate refresh token |
| `POST` | `/api/v1/auth/logout` | Revoke refresh token |
| `POST` | `/api/v1/auth/logout-all` | Revoke all tokens for user |
| `GET` | `/.well-known/jwks.json` | Public key set for JWT validation |

#### Buckets

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/buckets` | Create bucket |
| `GET` | `/api/v1/buckets` | List buckets for organization |
| `GET` | `/api/v1/buckets/{id}` | Get bucket details |
| `DELETE` | `/api/v1/buckets/{id}` | Delete bucket |

#### Objects (Upload)

| Method | Path | Description |
|---|---|---|
| `PUT` | `/api/v1/buckets/{id}/objects/{key}` | Single-part upload (≤100 MB) |
| `DELETE` | `/api/v1/buckets/{id}/objects/{key}` | Soft-delete object |
| `POST` | `/api/v1/uploads/initiate` | Initiate multipart upload |
| `PUT` | `/api/v1/uploads/{sessionId}/parts/{num}` | Upload a part |
| `POST` | `/api/v1/uploads/{sessionId}/complete` | Complete multipart upload |
| `GET` | `/api/v1/uploads/{sessionId}/status` | Get session status |
| `DELETE` | `/api/v1/uploads/{sessionId}` | Abort multipart upload |

#### Objects (Download)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/buckets/{id}/objects/{key}` | Download object (supports Range) |
| `GET` | `/api/v1/buckets/{id}/objects/{key}/versions` | List object versions |
| `POST` | `/api/v1/download/signed-urls` | Generate signed URL |
| `GET` | `/api/v1/download/signed` | Download via signed URL |

#### Admin

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/admin/overview` | Platform health and usage summary |
| `PUT` | `/api/v1/admin/quota` | Update organization quota |
| `GET` | `/api/v1/admin/audit` | Paginated audit log |
| `GET` | `/api/v1/admin/analytics/uploads` | Upload trend analytics |

### Example Request and Response

**Upload a file:**

```bash
curl -X PUT "http://localhost:80/api/v1/buckets/3fa85f64-5717-4562-b3fc-2c963f66afa6/objects/reports/q4-2024.pdf" \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9..." \
  -H "Content-Type: application/pdf" \
  -H "X-Checksum-SHA256: e3b0c44298fc1c149afb..." \
  --data-binary @q4-2024.pdf
```

**Response:**

```json
{
  "objectId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "versionId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "objectKey": "reports/q4-2024.pdf",
  "sizeBytes": 2048576,
  "checksumSha256": "e3b0c44298fc1c149afb4c8996fb92427ae41e4649b934ca495991b7852b855",
  "etag": "\"e3b0c44298fc1c149afb4c8996fb9242\"",
  "contentType": "application/pdf",
  "isDuplicate": false,
  "processingStatus": "PENDING",
  "uploadedAt": "2024-10-15T14:23:11.847Z"
}
```

---

## Configuration Reference

All services are configured via environment variables. Production values should be managed via Kubernetes Secrets or HashiCorp Vault.

### Shared Variables (All Services)

| Variable | Default | Required | Description |
|---|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/vaultflow` | ✅ | PostgreSQL JDBC connection URL |
| `DB_USERNAME` | `vaultflow` | ✅ | Database username |
| `DB_PASSWORD` | — | ✅ | Database password |
| `REDIS_HOST` | `localhost` | ✅ | Redis hostname |
| `REDIS_PORT` | `6379` | — | Redis port |
| `REDIS_PASSWORD` | — | Production | Redis password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | ✅ | Kafka broker addresses |
| `ENVIRONMENT` | `local` | — | Environment tag (`local`, `staging`, `production`) |
| `VAULTFLOW_JWT_PUBLIC_KEY_PATH` | — | ✅ | Path to RSA public key PEM file |

### Auth Service

| Variable | Default | Required | Description |
|---|---|---|---|
| `VAULTFLOW_JWT_PRIVATE_KEY_PATH` | — | ✅ | Path to RSA private key PEM file (auth-service only) |
| `VAULTFLOW_JWT_ACCESS_TOKEN_TTL` | `900` | — | Access token TTL in seconds (default 15 min) |
| `VAULTFLOW_JWT_REFRESH_TOKEN_TTL_DAYS` | `7` | — | Refresh token TTL in days |

### Upload Service

| Variable | Default | Required | Description |
|---|---|---|---|
| `STORAGE_BASE_DIR` | `/data/vaultflow/objects` | ✅ | Object storage root directory |
| `VAULTFLOW_UPLOAD_MAX_SINGLE_PART_BYTES` | `104857600` | — | Max single-part upload size (100 MB) |

### Download Service

| Variable | Default | Required | Description |
|---|---|---|---|
| `STORAGE_BASE_DIR` | `/data/vaultflow/objects` | ✅ | Must match upload-service mount |
| `SIGNED_URL_SECRET` | — | ✅ Production | HMAC-SHA256 secret for signed URL generation |
| `DOWNLOAD_BASE_URL` | `http://localhost:8083` | ✅ | Public base URL for signed URL generation |

### Processing Service

| Variable | Default | Required | Description |
|---|---|---|---|
| `STORAGE_BASE_DIR` | `/data/vaultflow/objects` | ✅ | Must match upload-service mount |
| `VAULTFLOW_PROCESSING_POOL_SIZE` | `8` | — | Virtual thread pool size for parallel processors |

---

## Security Model

### Authentication

VaultFlow uses **RS256 asymmetric JWT**. The auth-service holds the RSA private key and issues tokens. All other services hold only the public key for stateless validation — a private key compromise in one microservice cannot forge tokens.

```
Token lifecycle:

Login ──► access_token (15 min, stateless RS256 JWT)
      └─► refresh_token (7 days, stored SHA-256 hash in DB)

Refresh ──► new access_token
        └─► new refresh_token (rotation: old is revoked)
            Token family: if revoked token is reused,
            entire family is invalidated (theft detection)

Logout ──► refresh_token revoked in DB
       └─► access_token JTI added to Redis blacklist (15-min TTL)
```

### Authorization (RBAC)

| Role | Permissions |
|---|---|
| `OWNER` | Full control: manage users, update quotas, delete organization |
| `ADMIN` | Manage users and buckets, view audit logs |
| `EDITOR` | Upload, download, delete objects in permitted buckets |
| `VIEWER` | Download objects in permitted buckets |

### Content Security

- **Content-type validation**: Apache Tika inspects file magic bytes. Client-supplied `Content-Type` is recorded but never trusted for routing decisions.
- **Checksum verification**: SHA-256 provided via `X-Checksum-SHA256` header is verified before storage.
- **Virus scanning**: EICAR pattern detection (development). Production: integrate ClamAV via TCP socket.
- **Signed URL restrictions**: TTL expiry + optional IP CIDR restriction + download count limit.

### Transport

- TLS 1.3 via NGINX (configure certificates in `infrastructure/nginx/nginx.conf`)
- HSTS header enforced: `Strict-Transport-Security: max-age=63072000; includeSubDomains`
- Security headers: `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `X-XSS-Protection`

### Secrets Handling

- **Development**: environment variables in `docker-compose.yml`
- **Kubernetes**: Kubernetes Secrets (see `infrastructure/kubernetes/common/secrets-template.yaml`)
- **Production**: HashiCorp Vault Agent injection or AWS Secrets Manager

See [SECURITY.md](SECURITY.md) for the responsible disclosure process and security architecture.

---

## Observability

### Metrics (Prometheus + Grafana)

Key business metrics exposed at `/actuator/prometheus` on each service:

| Metric | Type | Description |
|---|---|---|
| `upload.files.total` | Counter | Total files uploaded |
| `upload.bytes.total` | Counter | Total bytes uploaded |
| `upload.singlepart.duration` | Timer | Single-part upload latency histogram |
| `upload.multipart.completed` | Counter | Completed multipart uploads |
| `processing.pipeline.duration` | Timer | End-to-end processing pipeline latency |
| `processing.pipeline.completed` | Counter | Processing pipeline completions |
| `download.bytes.total` | Counter | Total bytes downloaded |

Standard Spring Boot Actuator metrics for JVM, HTTP requests, Hikari connection pool, and Kafka consumer lag are also exposed.

### Service Level Objectives

| SLO | Target |
|---|---|
| Upload success rate | > 99.9% |
| Download p99 TTFB (Time to First Byte) | < 500 ms |
| Authentication p99 latency | < 300 ms |
| Processing pipeline p95 lag | < 30 seconds |
| Platform availability | > 99.95% |

### Distributed Tracing (OpenTelemetry → Jaeger)

All services propagate `X-Correlation-ID` and OpenTelemetry trace context. NGINX generates a correlation ID if the client does not provide one. Traces are exported to Jaeger at `localhost:4317` (OTLP gRPC).

Access the Jaeger UI at http://localhost:16686 to trace requests across service boundaries.

---

## Kubernetes Deployment

See `infrastructure/kubernetes/` for deployment manifests.

```bash
# Create namespace and shared configuration
kubectl apply -f infrastructure/kubernetes/common/

# Deploy services
kubectl apply -f infrastructure/kubernetes/auth/
kubectl apply -f infrastructure/kubernetes/upload/
kubectl apply -f infrastructure/kubernetes/download/

# Wait for rollout
kubectl rollout status deployment/auth-service -n vaultflow
kubectl rollout status deployment/upload-service -n vaultflow

# Scale manually
kubectl scale deployment/upload-service --replicas=10 -n vaultflow

# Emergency rollback
kubectl rollout undo deployment/upload-service -n vaultflow
```

### Disaster Recovery

| Objective | Target |
|---|---|
| RTO (Recovery Time Objective) | < 15 minutes |
| RPO (Recovery Point Objective) | < 5 minutes |

**Backup strategy:**
- **PostgreSQL**: Continuous WAL archiving + daily `pg_basebackup`
- **Redis**: AOF persistence + RDB snapshots every 60 seconds
- **Object storage**: 3-copy replication (production: S3 with cross-region replication)
- **Kafka**: Topic replication factor ≥ 3 in production clusters

Full recovery procedures in [docs/RUNBOOK.md](docs/RUNBOOK.md).

---

## Load Testing

```bash
# Install k6: https://k6.io/docs/get-started/installation/
k6 run infrastructure/load-test.js

# Parameterized run
k6 run \
  --env BASE_URL=http://localhost:80 \
  --vus 50 \
  --duration 5m \
  infrastructure/load-test.js
```

---

## Project Structure

```
vaultflow/
├── common/                          # Shared library (not a service)
│   └── src/main/java/com/vaultflow/common/
│       ├── dto/                     # Shared API response DTOs
│       ├── event/                   # Kafka event schemas (FileUploadedEvent, AuditEvent, ...)
│       ├── exception/               # Exception hierarchy and GlobalExceptionHandler
│       ├── security/                # JwtTokenProvider, auto-configuration, PEM utilities
│       ├── tracing/                 # CorrelationIdFilter
│       └── util/                    # ChecksumUtil (SHA-256, path routing)
│
├── auth-service/                    # Authentication and authorization
├── upload-service/                  # File upload pipeline
├── download-service/                # File download and signed URLs
├── metadata-service/                # Metadata search and lifecycle management
├── processing-service/              # Async file processing (Kafka consumer)
├── notification-service/            # Audit persistence and webhook delivery
├── admin-service/                   # Analytics, quota management, audit API
│
├── infrastructure/
│   ├── docker/                      # postgres-init.sql
│   ├── kubernetes/                  # K8s manifests: Deployment, Service, Ingress, HPA, PDB
│   ├── monitoring/
│   │   ├── prometheus/              # prometheus.yml scrape config
│   │   └── grafana/                 # Provisioned dashboards and datasources
│   ├── nginx/                       # nginx.conf (gateway, rate limiting, streaming)
│   └── load-test.js                 # k6 load test script
│
├── docs/
│   ├── ENGINEERING_DECISION_RECORDS.md   # Architectural Decision Records (EDR-001 through EDR-008)
│   ├── LOCAL_DEVELOPMENT.md              # Detailed developer setup guide
│   └── RUNBOOK.md                        # Operations runbook and incident playbooks
│
├── .github/
│   ├── workflows/ci-cd.yml          # 7-stage GitHub Actions CI/CD pipeline
│   └── ISSUE_TEMPLATE/              # Bug report and feature request templates
│
├── docker-compose.yml               # Full local stack with all infrastructure
├── install.sh                       # Ubuntu/WSL dependency installer
├── start.sh                         # Convenience startup script
└── pom.xml                          # Maven multi-module parent POM
```

---

## Design Decisions

Significant architectural decisions are documented in [docs/ENGINEERING_DECISION_RECORDS.md](docs/ENGINEERING_DECISION_RECORDS.md). A summary:

| EDR | Decision | Why |
|---|---|---|
| EDR-001 | SHA-256 content-addressed storage | Automatic deduplication; 20–70% storage savings in enterprise workloads |
| EDR-002 | Async Kafka processing pipeline | Upload latency unaffected by virus scan / thumbnail generation time |
| EDR-003 | RS256 asymmetric JWT | Private key stays in auth-service; other services validate with public key only |
| EDR-004 | Hexagonal architecture for storage | `LocalFileSystemStorage` → S3/GCS/MinIO swap without touching business logic |
| EDR-005 | Redis for upload session state | Avoids PostgreSQL row-lock contention under concurrent multipart uploads |
| EDR-006 | Java 21 Virtual Threads | Unlimited I/O concurrency without reactive programming; standard JDBC/JPA works |
| EDR-007 | Partitioned audit log table | Monthly partition `DETACH` for instant archival; partition pruning for range queries |
| EDR-008 | NGINX over Spring Cloud Gateway | Zero JVM overhead for routing; native streaming for large file uploads/downloads |

---

## Roadmap

See [ROADMAP.md](ROADMAP.md) for the full roadmap. Near-term priorities:

- [ ] S3-compatible adapter for `ObjectStoragePort` (MinIO / AWS S3)
- [ ] ClamAV TCP integration in `VirusScanProcessor`
- [ ] JWKS endpoint-based key rotation with dual-key window
- [ ] Webhook signing (HMAC-SHA256 on payload) for notification delivery
- [ ] Multi-region replication metadata
- [ ] OpenAPI 3.1 contract-first code generation

---

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

```bash
# Fork and clone
git clone https://github.com/your-username/vaultflow.git
cd vaultflow

# Create a feature branch
git checkout -b feature/your-feature-name

# Make changes, run tests
mvn test

# Check formatting
mvn spotless:check

# Push and open a Pull Request
git push origin feature/your-feature-name
```

---

## License

VaultFlow is licensed under the [MIT License](LICENSE).

---

<div align="center">

Built with Java 21 · Spring Boot 3 · Kafka · PostgreSQL · Redis

</div>
