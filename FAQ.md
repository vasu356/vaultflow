# Frequently Asked Questions

## General

**Q: What is VaultFlow?**

VaultFlow is a production-grade, self-hosted enterprise object storage platform. It provides S3-compatible file storage, async processing (thumbnails, virus scanning, metadata extraction), multi-tenant access control, and full observability — packaged as a Docker Compose stack or Kubernetes deployment.

**Q: How does VaultFlow compare to MinIO?**

MinIO is a pure S3-compatible object storage engine. VaultFlow is a full application platform built on top of local filesystem storage (or S3/MinIO as a backend). VaultFlow adds multi-tenant RBAC, async processing pipelines, content deduplication, signed URLs, audit logging, and organizational quota management — things you would build on top of MinIO yourself. They are complementary: VaultFlow can be configured to use MinIO or S3 as its storage backend via the pluggable `ObjectStoragePort`.

**Q: Is VaultFlow production-ready?**

The core platform is production-grade: hardened security (RS256 JWT, RBAC, virus scanning), Kubernetes manifests with HPA and PDB, a 7-stage CI/CD pipeline with security scanning, comprehensive observability, and disaster recovery procedures. The storage backend currently ships with `LocalFileSystemStorage`; production deployments should configure the S3 adapter (on the roadmap). See [ROADMAP.md](ROADMAP.md) for current limitations.

---

## Getting Started

**Q: How long does the first `docker-compose up` take?**

On a fast machine (M1/M2 Mac or modern Linux), approximately 3–5 minutes on first run (builds all 7 service Docker images). Subsequent starts are much faster due to layer caching.

**Q: Do I need Java and Maven installed to run VaultFlow?**

No. For running the platform, Docker is the only requirement. Java 21 and Maven 3.9 are only needed for local JVM development (modifying and running services outside Docker).

**Q: How do I reset to a clean state?**

```bash
docker-compose down -v  # Stops containers AND destroys all volumes (data)
docker-compose up -d    # Fresh start
```

This destroys all uploaded files, database data, and Redis cache.

---

## Authentication

**Q: Why do I get 401 on upload-service even with a valid token from auth-service?**

This is the JWT key sharing issue. In Docker Compose, if no key files are mounted, each service generates its own RSA key pair at startup. A token signed by auth-service's key does not validate in upload-service's key.

**Fix**: Generate a shared key pair in the `keys/` directory before starting:
```bash
mkdir -p keys
openssl genrsa -out keys/private.pem 2048
openssl rsa -in keys/private.pem -pubout -out keys/public.pem
docker-compose up -d
```

See [docs/LOCAL_DEVELOPMENT.md](docs/LOCAL_DEVELOPMENT.md) for details.

**Q: Why are access tokens only 15 minutes?**

Short-lived access tokens limit the damage window if a token is intercepted. Since tokens are stateless (RS256 signed), they cannot be immediately revoked — they must expire naturally. 15 minutes is the industry standard. Refresh tokens (7 days) are stored server-side and can be revoked instantly.

**Q: Can I have multiple users in one organization?**

Yes. One user registers an organization (they become `OWNER`). They can then invite additional users and assign roles (`ADMIN`, `EDITOR`, `VIEWER`). Organization membership is scoped — the same email address can exist in different organizations.

---

## Uploads

**Q: What is the maximum file size?**

- Single-part upload: 100 MB
- Multipart upload: 5 GB total, 500 MB per part (up to 10,000 parts)
- NGINX is configured for 5 GB maximum

**Q: What happens if my multipart upload is interrupted?**

Upload sessions are persisted in PostgreSQL with a 24-hour TTL. After an interruption, call `GET /api/v1/uploads/{sessionId}/status` to see which parts were already received. Upload only the missing parts, then complete.

**Q: Does VaultFlow deduplicate files automatically?**

Yes. VaultFlow uses SHA-256 content addressing. If you upload the same file twice (even with different object keys), only one physical copy is stored. The second upload returns `"isDuplicate": true` and does not count against your quota. Deduplication is scoped per organization for data isolation.

**Q: Can I upload from a browser (CORS)?**

NGINX does not have CORS headers configured by default. For browser uploads, add CORS headers to the relevant locations in `nginx.conf`. Signed URL generation from a backend server is the recommended approach for browser clients.

---

## Processing

**Q: How long does processing take after upload?**

Processing runs asynchronously. The upload response returns immediately with `"processingStatus": "PENDING"`. For most files:
- Virus scan: 0.1–2 seconds
- Image thumbnail: 0.1–1 second
- PDF preview: 1–3 seconds
- Video thumbnail: 2–10 seconds (depends on file size)

Processing runs in parallel — total time is max(individual processors), not the sum. Poll the metadata endpoint for status.

**Q: What happens if virus scanning fails?**

If the file is detected as infected (`virus_scan_status = 'INFECTED'`), downloads are blocked and the download-service returns 403. The file data is retained for forensic review. If processing encounters an unexpected error (not a detection), the event is retried up to 3 times, then sent to the Dead Letter Topic for manual inspection.

**Q: Can I add custom processing steps?**

Yes. Implement a class extending the processor pattern:

```java
@Component
public class CustomProcessor {
    public FileProcessedEvent process(FileUploadedEvent event) {
        // Your processing logic
    }
}
```

Then inject and call it from `ProcessingOrchestrator`. This is a planned interface stabilization in a future release.

---

## Storage

**Q: Where are uploaded files stored?**

In Docker Compose: the `vaultflow-object-storage` named Docker volume, mounted at `/data/vaultflow/objects` inside containers. In Kubernetes: a shared PersistentVolumeClaim.

The physical layout is:
```
/data/vaultflow/objects/{sha256[0:3]}/{sha256[3:6]}/{sha256}
```

**Q: How does content deduplication work with object versions?**

Each `ObjectVersion` has a `storage_key` (SHA-256 of content) and a `ref_count`. If two versions point to the same physical file, `ref_count` is > 1. Physical deletion only occurs when `ref_count` reaches 0.

**Q: What happens to files when I delete an object?**

Deletion is soft by default (`is_deleted = true`). The file data and `ObjectVersion` records are preserved. A background lifecycle scheduler handles actual deletion after the configured retention period. This enables restore and guarantees the audit trail.

---

## Security

**Q: Is data encrypted at rest?**

Currently, VaultFlow relies on volume-level encryption (e.g., LUKS on Linux, BitLocker on Windows, encrypted EBS volumes on AWS). Object-level server-side encryption is on the [ROADMAP](ROADMAP.md).

**Q: Are signed URLs secure?**

Signed URLs use HMAC-SHA256 with a server-side secret (`SIGNED_URL_SECRET`). They include:
- TTL expiry (configurable, default infinite if not set)
- Optional IP CIDR restriction
- Optional maximum download count

The token is not guessable without the server secret. However, anyone with the URL can download until it expires — treat them as time-limited credentials.

**Q: How do I report a security vulnerability?**

Email **security@vaultflow.io**. Do not open public GitHub issues for security reports. See [SECURITY.md](SECURITY.md) for the full responsible disclosure policy.

---

## Operations

**Q: How do I monitor VaultFlow?**

Open Grafana at http://localhost:3001 (admin/admin). The "VaultFlow Platform Overview" dashboard shows:
- Upload/download throughput and error rates
- Processing pipeline consumer lag
- JVM heap and GC metrics per service
- PostgreSQL connection pool usage
- Kafka topic lag

Prometheus is at http://localhost:9091. Jaeger distributed tracing is at http://localhost:16686.

**Q: How do I scale VaultFlow?**

Each service scales independently. In Docker Compose:
```bash
docker-compose up -d --scale upload-service=3
```

In Kubernetes:
```bash
kubectl scale deployment/upload-service --replicas=10 -n vaultflow
```

Maximum useful replicas for `processing-service` = number of partitions on `file.uploaded` topic (16 by default).

**Q: How do I back up VaultFlow?**

- **PostgreSQL**: Use `pg_dump` or continuous WAL archiving
- **Redis**: Redis persistence is configured (AOF + RDB). Back up the Redis data volume
- **Object storage**: Back up the `vaultflow-object-storage` volume or enable cross-region replication on S3

See [docs/RUNBOOK.md §4](docs/RUNBOOK.md) for database maintenance procedures.
