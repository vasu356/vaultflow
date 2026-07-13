# Engineering Decision Records (EDRs)

VaultFlow Platform — Architectural Decision Log
Each EDR documents a significant design choice, the problem it solves, alternatives considered, and trade-offs accepted.

---

## EDR-001: Content-Addressed Object Storage

**Date:** 2024-01  
**Status:** Accepted  
**Author:** Platform Engineering

### Problem
Enterprise storage platforms frequently receive duplicate file uploads — the same report uploaded by multiple team members, the same assets used in multiple projects. Storing each copy independently wastes capacity and bandwidth.

### Decision
Use SHA-256 content addressing as the storage key. Two objects with identical content share one physical file. Reference counting (`ref_count` on `object_versions`) tracks how many logical versions point to the same storage key. Physical deletion happens only when `ref_count` reaches 0.

Storage path layout:
```
{base}/{sha256[0:3]}/{sha256[3:6]}/{sha256}
```
Two-level sharding prevents inode exhaustion on filesystems that degrade with millions of files in one directory.

### Alternatives Considered
- **UUID-based keys**: Simpler, no dedup. Rejected — misses significant storage savings.
- **MD5 addressing**: Faster to compute. Rejected — MD5 has known collision vulnerabilities.
- **Org-scoped dedup only**: Dedup within org boundary (not cross-org). **Accepted** — data isolation concern outweighs cross-org dedup benefit.

### Trade-offs
- ✅ Storage savings proportional to duplicate rate (20–70% in enterprise scenarios)
- ✅ Bandwidth savings on upload (dedup detected before storage write)
- ⚠️ Quota accounting complexity — only charge for net-new storage
- ⚠️ Deletion complexity — must decrement ref_count, not delete immediately
- ⚠️ Security: cross-user timing side-channel (can infer if content exists). Mitigated by org-scope dedup only.

---

## EDR-002: Asynchronous Processing via Kafka

**Date:** 2024-01  
**Status:** Accepted

### Problem
File processing (thumbnails, virus scan, PDF preview) is CPU-intensive and slow. Blocking the upload HTTP response on processing would cause multi-second upload latency and tie up server threads.

### Decision
Upload service publishes `FileUploadedEvent` to Kafka after storing the object. Processing service consumes events asynchronously. Upload returns immediately (HTTP 200) with `processingStatus: PENDING`. Clients poll or use webhooks for processing completion.

### Alternatives Considered
- **Synchronous in-process processing**: Simple, no Kafka dependency. Rejected — p99 upload latency would include virus scan time (2–10s).
- **RabbitMQ**: Simpler to operate than Kafka. Rejected — Kafka's event replay capability enables re-processing on processor bug fix, critical for audit.
- **Async HTTP callback to processing service**: No broker. Rejected — lose durability; if processing service is down during upload, events are lost.

### Trade-offs
- ✅ Upload latency unaffected by processing pipeline
- ✅ Processing service scales independently of upload service
- ✅ Event replay for re-processing (Kafka retention 7 days)
- ✅ Dead Letter Topic for failed events with zero data loss
- ⚠️ Processing result is eventually consistent (not immediate)
- ⚠️ Operational complexity of Kafka cluster
- ⚠️ Client must poll for processing status or use webhooks

---

## EDR-003: RS256 JWT Authentication (Asymmetric over HMAC)

**Date:** 2024-01  
**Status:** Accepted

### Problem
With multiple services (upload, download, processing, admin), all must validate JWT tokens. HMAC-256 (symmetric) requires all services to share the secret — a secret leak in any service compromises the entire platform.

### Decision
Use RS256 (RSA SHA-256 asymmetric signing). Auth-service holds the private key and signs tokens. All other services hold only the public key for validation. Public key distributed via JWKS endpoint (`/.well-known/jwks.json`).

### Alternatives Considered
- **HMAC-256**: Simpler. Rejected — shared secret model is weaker; compromise of any service compromises auth.
- **Session tokens in Redis**: Stateful. Rejected — every request requires Redis lookup, adding 1–5ms latency. Stateless JWT is faster.
- **Opaque tokens**: Requires introspection endpoint call per request. Rejected — adds service-to-service latency.

### Trade-offs
- ✅ Private key stays in auth-service only
- ✅ Stateless validation in each service (no network call)
- ✅ Compatible with OAuth2 / OIDC standards
- ⚠️ Token revocation requires blacklist (Redis-based) since tokens are self-contained
- ⚠️ RSA signing is slower than HMAC (negligible at 15-min token TTL)
- ⚠️ Key rotation requires careful rollout (dual-key window)

---

## EDR-004: Hexagonal Architecture for Storage Layer

**Date:** 2024-01  
**Status:** Accepted

### Problem
Launching with local filesystem storage for simplicity, but production must migrate to object storage (S3/GCS/MinIO). If storage implementation is embedded in business logic, migration requires touching every service.

### Decision
Define `ObjectStoragePort` interface as the only storage contract business logic depends on. Provide `LocalFileSystemStorage` as the initial adapter. S3-compatible adapter can be swapped in without changing any service or controller code.

```java
// Business logic depends on this — never on implementation
public interface ObjectStoragePort {
    String store(String key, InputStream data, long size, String contentType);
    InputStream retrieve(String key);
    ...
}
```

### Trade-offs
- ✅ Storage migration is a single adapter swap
- ✅ Local filesystem enables development without AWS credentials
- ✅ Testable — mock the port in unit tests without filesystem
- ⚠️ Extra abstraction layer (minor complexity)

---

## EDR-005: Redis for Upload Session State

**Date:** 2024-01  
**Status:** Accepted

### Problem
Multipart uploads involve frequent small state updates (part received, part count, session status). Using PostgreSQL row-level locks for concurrent part writes creates contention under high upload concurrency.

### Decision
Store hot upload session state in Redis Hash structures during the upload. On session completion or expiry, the final state is persisted to PostgreSQL. Parts metadata is written to PostgreSQL for durability (crash recovery), but the hot path reads from Redis.

Redis key: `session:upload:{sessionId}` → Hash of session fields.
TTL: 24 hours (matching session expiry).

### Alternatives Considered
- **PostgreSQL only**: Simpler. Rejected — row-level lock contention at high part concurrency.
- **Redis only**: Rejected — durability concern; Redis persistence is async, a crash could lose a session.
- **In-memory (service-local)**: Rejected — breaks horizontal scaling (session affinity required).

### Trade-offs
- ✅ O(1) part receipt acknowledgment
- ✅ Supports 50,000 concurrent uploads with negligible DB load
- ⚠️ Redis adds operational dependency
- ⚠️ Redis eviction under memory pressure could lose session data (mitigated by PostgreSQL backup)

---

## EDR-006: Virtual Threads (Java 21) for HTTP and Processing

**Date:** 2024-01  
**Status:** Accepted

### Problem
Large file uploads involve blocking I/O (reading from network, writing to disk). Traditional thread-per-request with a bounded OS thread pool means upload concurrency is limited by pool size (typically 200 threads = 200 concurrent uploads).

### Decision
Enable Java 21 virtual threads via `spring.threads.virtual.enabled=true`. Tomcat switches to a virtual thread per request. Each upload can block on I/O without consuming an OS thread. Pool effectively scales to tens of thousands of concurrent requests.

Processing service uses `Executors.newVirtualThreadPerTaskExecutor()` for parallel processor execution.

### Alternatives Considered
- **WebFlux (reactive)**: Achieves similar throughput. Rejected — reactive programming model is complex, harder to debug, incompatible with JDBC/JPA (requires R2DBC migration).
- **Larger OS thread pool**: Rejected — OS threads are expensive (1–8 MB stack each). 10,000 threads = 80 GB stack memory.

### Trade-offs
- ✅ Near-unlimited upload concurrency with no code changes
- ✅ Standard blocking code (JDBC, Files.write) works correctly with virtual threads
- ✅ No reactive programming complexity
- ⚠️ Virtual threads require avoiding `synchronized` blocks on pinned carriers (use `ReentrantLock` instead)
- ⚠️ Java 21+ only — no downgrade path

---

## EDR-007: Partitioned Audit Logs

**Date:** 2024-01  
**Status:** Accepted

### Problem
Audit logs grow indefinitely. At 10,000 events/day per org with 100 orgs = 1M rows/day = 365M rows/year. A single unpartitioned table degrades query performance and makes archival expensive.

### Decision
Partition `audit_logs` by month using PostgreSQL declarative range partitioning on `occurred_at`. Each partition covers one month. Archival is a `DROP PARTITION` (instant, no row-by-row deletion). Retention policy: keep 12 months online, archive older partitions to cold storage.

### Alternatives Considered
- **Time-series DB (TimescaleDB)**: Better compression, faster queries. Rejected — adds operational complexity; PostgreSQL partitioning is sufficient for projected volume.
- **Elasticsearch**: Full-text search on audit logs. Deferred — can be added as a read replica.
- **Single table with aggressive indexing**: Fails above 100M rows for range queries.

### Trade-offs
- ✅ Monthly archival via `DETACH PARTITION` (zero downtime)
- ✅ Partition pruning makes time-range queries fast
- ✅ No separate time-series infrastructure
- ⚠️ Partition creation must be automated (pg_partman in production)
- ⚠️ Cross-partition queries (unbounded date range) do full scan

---

## EDR-008: NGINX over Spring Cloud Gateway

**Date:** 2024-01  
**Status:** Accepted

### Problem
An API gateway is needed for TLS termination, routing, rate limiting, and security headers. Two options evaluated: NGINX and Spring Cloud Gateway.

### Decision
Use NGINX. Handles TLS termination at L4/L7 in native C code. No JVM overhead for pure proxying. Connection-level rate limiting (`limit_req_zone`) is highly efficient. NGINX is battle-tested at Dropbox, Netflix, Cloudflare scale.

Critically: `proxy_request_buffering off` and `proxy_buffering off` enable streaming large uploads/downloads directly between client and backend — NGINX does not buffer the entire file in memory.

### Alternatives Considered
- **Spring Cloud Gateway**: Reactive, Java-based. Benefits: service discovery integration, dynamic routing. Rejected — adds JVM to the critical path; streaming large files through a JVM gateway risks memory pressure.
- **Envoy**: Feature-rich, Kubernetes-native. Would be preferred in a service-mesh architecture. Deferred — overkill for current scale.
- **AWS ALB/NLB**: Managed. Valid for cloud deployment. Excluded — we target infrastructure-agnostic deployment.

### Trade-offs
- ✅ Zero JVM overhead for routing and TLS
- ✅ Native streaming for large files (no memory buffering)
- ✅ Mature rate limiting, connection limiting, security headers
- ⚠️ Dynamic routing requires reload or Lua scripting
- ⚠️ Less service-discovery integration than Spring Cloud Gateway
