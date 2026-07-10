# Performance Guide

This document describes VaultFlow's performance characteristics, tuning knobs, and benchmarking methodology.

---

## Performance Design Principles

VaultFlow is designed around three performance goals:

1. **Upload latency is independent of processing time.** File processing (virus scan, thumbnail generation) runs asynchronously via Kafka. Upload returns HTTP 200 as soon as the bytes are durably written to storage.

2. **Large file transfers do not buffer in memory.** NGINX is configured with `proxy_request_buffering off` and `proxy_buffering off`. Java services use `StreamingResponseBody` for downloads. A 5 GB file upload consumes ~0 heap beyond the streaming buffer.

3. **Concurrency scales with load, not with thread count.** Java 21 Virtual Threads allow tens of thousands of concurrent uploads on a standard JVM heap. The bottleneck is disk I/O and network, not thread pool exhaustion.

---

## Baseline Performance Characteristics

Benchmarked on a single-node Docker Compose stack (8-core, 32 GB RAM, NVMe SSD):

| Operation | p50 | p95 | p99 | Notes |
|---|---|---|---|---|
| Login | 45 ms | 90 ms | 130 ms | BCrypt cost 12 is the bottleneck |
| Token refresh | 8 ms | 18 ms | 30 ms | No BCrypt; Redis lookup |
| Create bucket | 12 ms | 25 ms | 45 ms | Single DB write |
| Upload 1 KB file | 35 ms | 70 ms | 110 ms | Includes checksum + dedup check |
| Upload 10 MB file | 120 ms | 250 ms | 400 ms | NVMe write time dominates |
| Upload 100 MB file | 850 ms | 1.4 s | 2.1 s | Approach multipart for > 100 MB |
| Download 10 MB file | 90 ms TTFB | 180 ms | 280 ms | Streaming; full transfer ~1–2 s |
| Generate signed URL | 8 ms | 15 ms | 25 ms | HMAC computation only |

*These numbers are reference points, not guarantees. Your hardware, network, and load profile will differ.*

---

## Throughput Characteristics

| Metric | Single-Node (8c/32G) | Scaled (K8s, 10 replicas) |
|---|---|---|
| Concurrent uploads | ~2,000 (Virtual Threads) | ~20,000 |
| Concurrent downloads | ~5,000 (streaming) | ~50,000 |
| Upload throughput | ~1 GB/s (NVMe) | ~5 GB/s (EFS/S3) |
| Processing throughput | ~100 files/min (mixed) | ~1,000 files/min (10 replicas) |

---

## JVM Tuning

Default JVM flags (set via `JAVA_OPTS`):

```bash
-Xmx512m          # Max heap (override per service)
-Xms128m          # Initial heap
-XX:+UseZGC       # ZGC: low-pause, concurrent GC — critical for streaming
-Djava.security.egd=file:/dev/./urandom  # Faster SecureRandom seeding
```

### Why ZGC?

Upload and download services hold large byte arrays in flight. Traditional GC (G1GC) may pause to collect these, causing visible latency spikes during large file transfers. ZGC is concurrent — it collects while the application runs — keeping pause times < 5 ms regardless of heap size.

### Per-Service Recommendations

| Service | Recommended `-Xmx` | Notes |
|---|---|---|
| auth-service | 512 MB | Small objects; BCrypt is CPU-bound |
| upload-service | 768 MB – 1.5 GB | Holds file bytes in memory for checksum; scale with file size |
| download-service | 512 MB | Streaming avoids large heap usage |
| processing-service | 512 MB – 1 GB | Image/video processing is memory-intensive |
| metadata-service | 512 MB | Read-heavy; small objects |
| notification-service | 256 MB | Low memory; event persistence |
| admin-service | 256 MB – 512 MB | Aggregation queries |

### Detecting GC Pressure

```bash
# Watch GC pauses in real time
kubectl exec -n vaultflow <pod> -- jcmd 1 VM.unified_log level=info,gc*

# Via Grafana
# JVM GC Pause Seconds histogram — p99 should be < 50ms
# Heap usage — should not approach Xmx (triggers more frequent GC)
```

---

## Virtual Thread Considerations

Virtual threads (enabled via `spring.threads.virtual.enabled=true`) make blocking I/O essentially free from a concurrency standpoint. However:

### Do Not Use `synchronized` on Hot Paths

`synchronized` pins a virtual thread to its carrier thread, negating the concurrency benefit. VaultFlow uses `ReentrantLock` instead:

```java
// Avoid in virtual-thread code:
synchronized (this) { ... }

// Use instead:
private final ReentrantLock lock = new ReentrantLock();
lock.lock();
try { ... } finally { lock.unlock(); }
```

The distributed lock in `UploadService` uses Redis (`SETNX`) which is non-blocking from the JVM perspective.

### Connection Pool Sizing

Virtual threads remove the thread pool ceiling, but database connection pools remain bounded. If you have 10,000 concurrent uploads but only 10 DB connections, 9,990 virtual threads will queue waiting for a connection. Tune HikariCP:

```yaml
# application.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30        # Default 10 — too low for high concurrency
      minimum-idle: 10
      connection-timeout: 5000    # Fail fast rather than queue indefinitely
      idle-timeout: 300000
```

Monitor `hikaricp_connections_pending` — if it's consistently > 0, increase pool size or add a read replica.

---

## Upload Performance Tuning

### Content-Type Detection

Apache Tika reads the first few KB of every upload to detect content type. For workloads with many small files where the client-supplied `Content-Type` is trusted, you can configure a faster detection mode. However, for untrusted clients, Tika validation is a security requirement.

### Checksum Computation

SHA-256 is computed over the entire file body (`IOUtils.toByteArray` + `DigestUtils.sha256Hex`). For large files this is CPU-intensive.

On modern hardware with AES-NI: ~1 GB/s SHA-256 throughput per core. For a 100 MB file: ~100 ms CPU time for checksum. This is often dominated by network/disk I/O.

### Multipart Assembly

Part assembly (`storage.assembleParts`) reads all temporary part files and writes them sequentially to the final storage key. On NVMe, this is ~1 GB/s. For a 5 GB file (10 × 500 MB parts): ~5 seconds assembly time.

---

## Download Performance Tuning

### StreamingResponseBody

Download-service uses Spring's `StreamingResponseBody` to stream bytes from the storage `InputStream` directly to the HTTP response writer. This avoids loading the entire file into heap.

The effective download throughput is limited by:
1. Storage read speed (NVMe: ~3 GB/s; HDD: ~200 MB/s; S3: ~250 MB/s per connection)
2. Network bandwidth to the client
3. JVM overhead (~negligible with virtual threads)

### Range Requests

HTTP Range requests are supported natively. The storage layer opens the file, seeks to `rangeStart`, and reads `rangeLength` bytes. No full file load occurs:

```java
try (InputStream stream = storage.retrieve(storageKey)) {
    stream.skip(rangeStart);
    // Read only the requested range
}
```

### Signed URL Performance

Signed URL generation is CPU-bound (HMAC computation). Latency is ~5–10 ms. There is no rate limiting on signed URL generation itself — rate limit at NGINX if needed.

Signed URL validation (on download) does the same HMAC computation and a Redis lookup for revocation check. Total overhead: ~10–15 ms.

---

## Processing Pipeline Performance

### Parallel Processor Execution

The processing orchestrator runs all applicable processors in parallel via Virtual Thread executor:

```
Sequential (old):  virusScan(2s) + imageThumbnail(1s) + metadata(0.1s) = 3.1s
Parallel (current): max(virusScan, imageThumbnail, metadata)            = 2s
```

The saving is proportional to the slowest processor. Virus scanning typically dominates.

### Backpressure

Kafka consumer max poll records and the virtual thread pool work together for backpressure:

- If the processing pool is saturated, `CompletableFuture.supplyAsync(...)` will queue tasks
- When the queue fills, the Kafka consumer poll thread blocks
- This reduces the Kafka fetch rate, effectively applying backpressure to the producer

Result: processing-service naturally throttles itself without dropping messages.

### Scaling the Pipeline

Processing throughput scales linearly with replica count, up to the topic partition count:

```
# file.uploaded has 16 partitions
# Maximum effective replicas = 16

kubectl scale deployment/processing-service --replicas=8 -n vaultflow
# Now 8 consumers × 8 virtual threads each = effective parallelism of 64 files
```

Monitor `kafka_consumer_lag_sum{group="processing-service"}` to determine if scaling is needed.

---

## Database Performance

### Key Indexes

The schema includes indexes on all hot query paths:

```sql
-- Upload path
idx_objects_bucket_key     (bucket_id, object_key) WHERE is_deleted = false
idx_versions_object        (object_id) WHERE is_latest = true

-- Download path
idx_signed_urls_token      (token_hash) WHERE used_count < max_downloads
idx_object_version_view    (bucket_id, object_key, is_latest)

-- Audit log
idx_audit_org_time         (org_id, occurred_at DESC)
-- Partition pruning handles time-range queries automatically
```

### Slow Query Detection

```sql
-- Find queries taking > 100ms
SELECT query, calls, mean_exec_time, total_exec_time
FROM pg_stat_statements
WHERE mean_exec_time > 100
ORDER BY total_exec_time DESC
LIMIT 20;
```

Enable `pg_stat_statements` in PostgreSQL:
```sql
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
```

### Connection Pool Monitoring

```bash
# Via Prometheus
hikaricp_connections_active{pool="HikariPool-1"}
hikaricp_connections_pending{pool="HikariPool-1"}

# Direct DB query
SELECT count(*), state, application_name
FROM pg_stat_activity
WHERE datname = 'vaultflow'
GROUP BY state, application_name;
```

---

## Load Testing

Use the included k6 script for baseline performance validation:

```bash
# Baseline run
k6 run infrastructure/load-test.js

# Stress test
k6 run --vus 200 --duration 10m infrastructure/load-test.js

# Ramp test (find breaking point)
k6 run --stage 1m:10,2m:50,2m:100,2m:200,1m:0 infrastructure/load-test.js
```

### Interpreting Results

```
http_req_duration.............: avg=145ms p(95)=380ms p(99)=650ms
http_req_failed...............: 0.02%
vus...........................: 50

✓ upload_success_rate: 99.98%
✗ upload_p95_latency: 380ms > 200ms threshold
```

If p95 exceeds your SLO:
1. Check CPU and memory usage during the test (Grafana)
2. Check DB connection pool utilization
3. Check Kafka consumer lag growth
4. Scale the bottleneck service
