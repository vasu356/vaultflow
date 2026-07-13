# Roadmap

This document describes planned improvements to VaultFlow. Items are organized by priority and expected effort. This is a living document — priorities may shift based on community feedback and production learnings.

Contributions are welcome on any item. See [CONTRIBUTING.md](CONTRIBUTING.md) to get started.

---

## Near-Term (Next Release)

### S3-Compatible Storage Adapter

**Motivation**: The current `LocalFileSystemStorage` adapter works for development and on-premise deployment, but most production deployments need managed object storage.

**Work**: Implement `S3ObjectStoragePort` implementing the existing `ObjectStoragePort` interface. The hexagonal architecture (EDR-004) means zero changes to any service or controller code.

```java
// Adapter to implement
public class S3ObjectStoragePort implements ObjectStoragePort {
    // S3 / MinIO / GCS client
}
```

Activation via environment variable: `STORAGE_ADAPTER=s3` (default: `local`).

Target providers: AWS S3, MinIO (self-hosted), Google Cloud Storage (via S3-compatible API).

### ClamAV Integration

**Motivation**: The current `VirusScanProcessor` uses EICAR pattern detection for development. Production deployments need a real antivirus engine.

**Work**: Add a configurable ClamAV TCP socket client in `VirusScanProcessor`. When `CLAMAV_HOST` is set, delegate to ClamAV; otherwise fall back to EICAR detection.

```bash
# Enable in docker-compose.yml:
CLAMAV_HOST: clamav
CLAMAV_PORT: 3310
```

### Webhook Payload Signing

**Motivation**: Consumers of VaultFlow webhooks need to verify that the payload originated from VaultFlow and was not tampered with in transit.

**Work**: Sign webhook payloads with HMAC-SHA256 using a per-organization webhook secret. Include `X-VaultFlow-Signature: sha256=<hex>` header. Document verification in the webhook consumer guide.

---

## Medium-Term

### JWKS Key Rotation

**Motivation**: RSA key pairs should be rotated periodically. Manual rotation currently requires a 15-minute downtime window for access token expiry.

**Work**: 
1. Auth-service serves multiple keys at `/.well-known/jwks.json` during rotation window
2. Add `kid` (key ID) header to all issued JWTs
3. Services select the correct public key by `kid` for validation
4. After all tokens with the old `kid` expire, the old key is removed from JWKS

### Object Lifecycle Policies

**Motivation**: Enterprise storage requires automatic expiry and archival rules (e.g., "delete objects older than 90 days", "move to cold storage after 30 days").

**Work**: 
- Extend `metadata-service/LifecycleScheduler` with configurable retention rules stored in PostgreSQL
- Soft-delete objects matching expired rules
- Publish lifecycle events to Kafka for audit trail
- Admin API for creating and managing lifecycle policies per bucket

### Server-Side Encryption

**Motivation**: Objects should be encrypted at rest. Currently VaultFlow relies on filesystem/volume encryption.

**Work**:
- Generate per-object AES-256 data encryption keys (DEKs)
- Encrypt DEKs with an organization master key (KEK) stored in a KMS (AWS KMS / HashiCorp Vault Transit)
- Store encrypted DEK alongside object metadata in PostgreSQL
- Decrypt DEK on download in download-service

### Multi-Region Metadata Replication

**Motivation**: Active-active multi-region deployments need metadata consistency across regions.

**Work**:
- Evaluate: Kafka MirrorMaker 2 for topic replication vs. PostgreSQL logical replication
- Document conflict resolution strategy for concurrent writes to the same object key

---

## Long-Term

### OpenAPI Contract-First Code Generation

**Motivation**: Keeping hand-written controllers, DTOs, and API documentation synchronized is error-prone. Contract-first enforces the API spec as the single source of truth.

**Work**:
- Define OpenAPI 3.1 specs for all services in `docs/api/`
- Configure `openapi-generator-maven-plugin` to generate DTOs and controller interfaces
- Controllers implement the generated interfaces

### Event Schema Registry

**Motivation**: As Kafka event schemas evolve (`schemaVersion` is already present in `FileUploadedEvent`), producers and consumers need to coordinate schema evolution safely.

**Work**: Integrate Confluent Schema Registry (or AWS Glue Schema Registry). Enforce backward compatibility checks in CI.

### Observability Enhancements

- **Exemplars**: Link Prometheus metrics to Jaeger traces via exemplar labels for jump-to-trace from dashboards
- **SLO dashboards**: Grafana SLO panels with error budget burn rate alerts
- **Kafka consumer lag alerts**: Prometheus alerting rules for `processing-service` consumer group lag > 5,000 messages

### Administration UI

**Motivation**: A web-based admin dashboard for non-technical operators to manage quotas, view audit logs, and monitor platform health without using the CLI.

**Work**: React SPA consuming the admin-service API.

---

## Completed

- ✅ Multi-tenant RBAC with organization isolation
- ✅ RS256 asymmetric JWT with refresh token rotation
- ✅ SHA-256 content-addressed deduplication
- ✅ Resumable multipart upload with Redis session state
- ✅ Async Kafka processing pipeline with DLT and replay
- ✅ Java 21 Virtual Threads for unlimited I/O concurrency
- ✅ HTTP Range request support
- ✅ HMAC-SHA256 signed URLs
- ✅ Monthly-partitioned audit log
- ✅ Prometheus + Grafana + Jaeger observability stack
- ✅ 7-stage CI/CD pipeline with Trivy security scanning
- ✅ Kubernetes manifests with HPA and PDB

---

## Providing Feedback

To suggest a new roadmap item or vote for an existing one, open a [GitHub Discussion](https://github.com/your-org/vaultflow/discussions/categories/ideas) or comment on the relevant GitHub Issue.
