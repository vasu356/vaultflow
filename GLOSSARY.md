# Glossary

Domain-specific and platform terms used throughout VaultFlow documentation and code.

---

## A

**Access Token**
A short-lived (15-minute) RS256-signed JWT that authenticates a user to VaultFlow services. Stateless — each service validates the token locally using the RSA public key without a network call.

**AuditEvent**
A Kafka event published to `audit.events` for every write operation in the platform. Persisted to the partitioned `audit_logs` PostgreSQL table by the notification-service.

---

## B

**Bucket**
A named container for objects within an organization. Analogous to an S3 bucket. Buckets provide a namespace for object keys — the same object key can exist in different buckets.

---

## C

**Carrier Thread**
In Java 21 Virtual Thread terminology, the OS thread that "carries" a virtual thread during execution. Virtual threads are lightweight and can be unmounted from their carrier thread when they block on I/O.

**Checksum (SHA-256)**
A 256-bit hash of file content used for two purposes: (1) integrity verification — clients can provide an expected checksum and the upload-service verifies it before storage; (2) deduplication key — two files with the same SHA-256 share one physical storage copy.

**Content-Addressed Storage**
Storage where the address (key) of a file is derived from its content (SHA-256 hash), not from a user-supplied name. VaultFlow uses this for the physical storage layer, enabling automatic deduplication. See EDR-001.

**Correlation ID**
A UUID propagated as `X-Correlation-ID` HTTP header across all service boundaries and included in every log line for a request. Enables tracing a single user request across multiple services in logs.

---

## D

**Dead Letter Topic (DLT)**
A Kafka topic that receives events that could not be processed after all retries are exhausted. VaultFlow maintains DLTs for all processing and notification topics (e.g., `file.uploaded.DLT`). Events in DLTs require manual inspection and replay.

**Deduplication**
The process of identifying and merging identical file content to avoid storing multiple physical copies. VaultFlow deduplicates per organization (not cross-organization) using SHA-256 content addressing. When a duplicate is detected, the upload returns `isDuplicate: true` and quota is not consumed.

---

## E

**EDR (Engineering Decision Record)**
A document recording a significant architectural decision, the problem it solves, alternatives considered, and trade-offs accepted. VaultFlow's EDRs are in [docs/ENGINEERING_DECISION_RECORDS.md](docs/ENGINEERING_DECISION_RECORDS.md). Analogous to ADRs (Architectural Decision Records).

**ETag**
An opaque string identifying a specific version of a resource. VaultFlow generates ETags as a quoted truncated SHA-256 for single-part uploads, and as a quoted `md5hash-partcount` for multipart uploads (compatible with S3 multipart ETag format).

---

## F

**Family ID**
A UUID shared by all refresh tokens in a rotation chain. When a refresh token is used, the old token is revoked and a new token with the same `family_id` is issued. If a previously-revoked token in a family is presented, all tokens in that family are invalidated (theft detection).

**FileProcessedEvent**
A Kafka event published to `file.processed` after all processors (virus scan, thumbnail, etc.) complete for an uploaded file. Consumed by notification-service for audit logging and webhook delivery.

**FileUploadedEvent**
A Kafka event published to `file.uploaded` by the upload-service when a file is successfully stored. Contains metadata including content type, size, checksum, and processing flags. Consumed by processing-service.

---

## H

**Hexagonal Architecture (Ports and Adapters)**
An architectural pattern where business logic depends on interfaces (ports) rather than implementations. VaultFlow uses this for storage: `ObjectStoragePort` is the port; `LocalFileSystemStorage` is the current adapter. Swapping to S3 requires only a new adapter, not changes to business logic. See EDR-004.

---

## J

**JWKS (JSON Web Key Set)**
A JSON document containing public keys in a standard format, served at `/.well-known/jwks.json` by auth-service. Other services fetch the public key from this endpoint for JWT validation, enabling key rotation without redeployment.

**JWT (JSON Web Token)**
A compact, URL-safe token format used for authentication. VaultFlow uses RS256-signed JWTs for access tokens. The header, payload, and signature are base64-encoded and period-separated.

---

## M

**Multipart Upload**
A resumable upload mechanism for large files (>100 MB). The client initiates a session, uploads numbered parts independently (can be parallel), then completes the session to assemble the final object. Sessions persist for 24 hours, enabling resume after interruption.

---

## O

**Object**
A file stored in VaultFlow. Identified by a bucket and an object key (path-like string). Objects support versioning — each PUT creates a new `ObjectVersion`, and previous versions are preserved.

**ObjectStoragePort**
The Java interface defining the storage contract that business logic depends on. Current implementation: `LocalFileSystemStorage`. Future implementations: S3, MinIO, GCS.

**ObjectVersion**
A specific version of an object, created on every upload. Contains the storage key, checksum, size, content type, processing status, and virus scan result. The `isLatest` flag marks the current active version.

**org_id**
The UUID of the organization that owns a resource. Used as the primary tenancy boundary in all queries and access control checks.

---

## P

**Part**
One chunk of a multipart upload. Minimum size: 5 MB (except the last part). Maximum size: 500 MB. Parts are stored temporarily during an upload session and assembled into the final object on completion.

**Processing Pipeline**
The async sequence of operations performed on a file after upload: virus scanning, thumbnail/preview generation, and metadata extraction. Triggered by a Kafka event. All processors run in parallel via a Virtual Thread executor.

**Processing Status**
The state of the async processing pipeline for an object version. Values: `PENDING` (waiting for processing), `PROCESSING` (in progress), `COMPLETED` (all processors finished), `FAILED` (processing failed after retries).

---

## Q

**Quota**
The maximum total storage (in bytes) allocated to an organization. Default: 100 GB. Enforced transactionally during uploads — `used_bytes` is incremented atomically and checked against `quota_bytes`. Deduplication means identical files do not consume quota twice.

---

## R

**RBAC (Role-Based Access Control)**
VaultFlow's authorization model. Users are assigned one role per organization: `OWNER`, `ADMIN`, `EDITOR`, or `VIEWER`. Role hierarchy: `OWNER > ADMIN > EDITOR > VIEWER`.

**Ref Count**
The `ref_count` column on `object_versions` tracks how many logical versions reference the same physical storage key (SHA-256 hash). Physical deletion occurs only when `ref_count` reaches 0, preventing premature deletion when deduplication is active.

**Refresh Token**
A long-lived (7-day) token used to obtain new access tokens without re-authentication. Stored as SHA-256 hash in PostgreSQL. Supports rotation (each use invalidates the old token) and revocation.

**RS256**
The JWT signing algorithm used by VaultFlow: RSA with SHA-256. Asymmetric — the private key signs tokens; the public key verifies them. Auth-service is the sole holder of the private key. See EDR-003.

---

## S

**Signed URL**
A time-limited, unauthenticated URL for downloading a specific object version. Signed with HMAC-SHA256 using the server-side `SIGNED_URL_SECRET`. Supports TTL expiry, IP restriction, and maximum download count. No `Authorization` header required to use.

**Soft Delete**
The deletion model for objects: `is_deleted = true` is set in the database, but data is not immediately removed. Enables restore and preserves audit history. Physical deletion is performed by a background lifecycle scheduler.

**Storage Key**
The physical address of file content in the storage layer. Equals the SHA-256 hash of the file content. Used as the path in the two-level directory structure: `{hash[0:3]}/{hash[3:6]}/{hash}`.

---

## T

**Token Family**
All refresh tokens issued in a single rotation chain (original token + all tokens issued via refresh from it). Share a `family_id`. Token family invalidation is VaultFlow's mechanism for detecting and responding to refresh token theft.

---

## U

**Upload Session**
A server-side record of an in-progress multipart upload. Contains the bucket, object key, part count, received parts, and expiry. Persisted in PostgreSQL; hot state cached in Redis. Sessions expire after 24 hours of inactivity.

---

## V

**Virtual Thread**
A Java 21 feature (Project Loom). Lightweight threads managed by the JVM rather than the OS. Thousands of virtual threads can exist concurrently mounted on a small pool of OS carrier threads. VaultFlow uses virtual threads for HTTP request handling and the processing pipeline, enabling high upload/download concurrency without reactive programming. See EDR-006.

**Virus Scan Status**
The result of the antivirus check on an object version. Values: `PENDING`, `CLEAN`, `INFECTED`, `ERROR`. Downloads are blocked for `INFECTED` objects.
