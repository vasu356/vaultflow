# Observability

VaultFlow is fully instrumented with metrics, distributed tracing, and structured logging. This document explains the observability architecture and how to use it.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   VaultFlow Services                     │
│                                                         │
│  Micrometer (metrics)    OpenTelemetry (traces)         │
│       │                        │                        │
│       │ /actuator/prometheus    │ OTLP gRPC :4317        │
└───────┼────────────────────────┼────────────────────────┘
        │                        │
        ▼                        ▼
   Prometheus               Jaeger
   (metrics store)          (trace store)
        │
        ▼
   Grafana
   (dashboards + alerts)
```

NGINX access logs (JSON-structured) and application logs (JSON with MDC fields) can be shipped to a log aggregator (Loki, ELK, Datadog) using a sidecar or log driver.

---

## Metrics

### Accessing Prometheus

```
http://localhost:9091                  # Prometheus UI
http://localhost:8081/actuator/prometheus  # Raw metrics for auth-service
http://localhost:8082/actuator/prometheus  # Raw metrics for upload-service
```

All Spring Boot Actuator auto-instrumentation is enabled by default, plus VaultFlow-specific business metrics.

### Standard Metrics (All Services)

| Metric | Type | Labels | Description |
|---|---|---|---|
| `http_server_requests_seconds` | Timer | `method`, `uri`, `status` | HTTP request latency and count |
| `jvm_memory_used_bytes` | Gauge | `area`, `id` | JVM heap and non-heap usage |
| `jvm_gc_pause_seconds` | Timer | `action`, `cause` | GC pause duration |
| `hikaricp_connections_active` | Gauge | `pool` | Active DB connections |
| `hikaricp_connections_pending` | Gauge | `pool` | Threads waiting for a connection |
| `kafka_consumer_lag_sum` | Gauge | `group`, `topic` | Consumer group lag |

### VaultFlow Business Metrics

| Metric | Type | Service | Description |
|---|---|---|---|
| `upload.files.total` | Counter | upload | Total files uploaded (increments per upload) |
| `upload.bytes.total` | Counter | upload | Total bytes uploaded |
| `upload.singlepart.duration` | Timer | upload | Single-part upload end-to-end latency |
| `upload.multipart.completed` | Counter | upload | Completed multipart uploads |
| `processing.pipeline.duration` | Timer | processing | Time from event receipt to all processors complete; labeled by `contentType` |
| `processing.pipeline.completed` | Counter | processing | Successful pipeline completions |
| `download.bytes.total` | Counter | download | Total bytes served via download |

### Useful PromQL Queries

```promql
# Upload success rate (last 5 minutes)
1 - (
  rate(http_server_requests_seconds_count{service="upload-service",status=~"5.."}[5m])
  /
  rate(http_server_requests_seconds_count{service="upload-service"}[5m])
)

# Upload p99 latency
histogram_quantile(0.99,
  rate(http_server_requests_seconds_bucket{service="upload-service",uri="/api/v1/buckets/{id}/objects/{key}"}[5m])
)

# Processing pipeline p95 latency
histogram_quantile(0.95,
  rate(processing_pipeline_duration_seconds_bucket[5m])
)

# Kafka consumer lag for processing-service
kafka_consumer_lag_sum{group="processing-service"}

# Active DB connections across all services
sum by (service) (hikaricp_connections_active)

# Total upload throughput (bytes/second)
rate(upload_bytes_total[5m])
```

---

## Grafana Dashboards

Grafana runs at `http://localhost:3001` (admin/admin).

### Pre-built Dashboards

The **VaultFlow Platform Overview** dashboard (`infrastructure/monitoring/grafana/dashboards/vaultflow-overview.json`) includes:

- **Upload throughput**: Files/second and MB/second by time
- **Error rates**: HTTP 5xx rates per service
- **Latency**: p50/p95/p99 for upload and download endpoints
- **Processing pipeline**: Consumer lag and pipeline duration
- **JVM health**: Heap usage, GC frequency, active threads — per service
- **Database**: HikariCP pool utilization, connection wait time
- **Kafka**: Consumer lag per group and topic

### Adding the Dashboard

```bash
# Dashboards are auto-provisioned from infrastructure/monitoring/grafana/dashboards/
# If running Grafana manually, import via UI:
# Dashboards → Import → Upload JSON file
```

### Creating Alerts

```bash
# In Grafana:
# Alert Rules → New Alert Rule → "Upload error rate"
# Expression: rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.001
# For: 5 minutes
# Labels: severity=critical
# Notifications: configure contact point (PagerDuty, Slack, etc.)
```

---

## Distributed Tracing (Jaeger)

### Accessing Jaeger

```
http://localhost:16686
```

### Trace Propagation

VaultFlow propagates OpenTelemetry context (`traceparent` header) across all service calls. The NGINX gateway:

1. Generates `X-Request-ID` (NGINX request ID)
2. Passes `X-Correlation-ID` (from client or generated) to all backends
3. Passes `traceparent` if present in the client request

The `CorrelationIdFilter` in every service injects `correlationId` into the SLF4J MDC, so it appears in every log line for that request.

### Example: Tracing an Upload

1. Open Jaeger UI at http://localhost:16686
2. Select service: `upload-service`
3. Find traces with `Operation: PUT /api/v1/buckets/{id}/objects/{key}`
4. Click a trace to see the span tree:
   - `PUT /api/v1/buckets/{id}/objects/{key}` (upload-service)
     - `QuotaService.assertQuota`
     - `ContentTypeDetector.detect`
     - `ChecksumUtil.sha256Hex`
     - `LocalFileSystemStorage.store`
     - `KafkaTemplate.send` → `file.uploaded`

For cross-service traces (upload → processing), the `traceparent` header is embedded in the Kafka message headers, allowing Jaeger to link the upload trace to the processing trace.

### Correlating Logs and Traces

Every log line includes `X-Correlation-ID`. Every Jaeger span has `correlationId` as a tag. To move from a log entry to its trace:

```bash
# Find correlation ID in logs
docker-compose logs upload-service | grep "correlationId=abc-123"

# Search for that trace in Jaeger
# Tags → correlationId = abc-123
```

---

## Structured Logging

### Format

All services log JSON to stdout. Example log line:

```json
{
  "timestamp": "2024-10-15T14:23:11.847Z",
  "level": "INFO",
  "service": "upload-service",
  "traceId": "4e2b3f1a9c8d7e6f",
  "spanId": "2a1b3c4d",
  "correlationId": "req-uuid-abc123",
  "orgId": "org-uuid",
  "userId": "user-uuid",
  "message": "Upload complete: objectId=obj-uuid versionId=ver-uuid size=2048576 dedup=false"
}
```

### NGINX Access Logs

NGINX is configured with `json_combined` log format:

```json
{
  "time": "2024-10-15T14:23:11+00:00",
  "remote_addr": "10.0.0.1",
  "method": "PUT",
  "uri": "/api/v1/buckets/uuid/objects/reports/q4.pdf",
  "status": 200,
  "bytes_sent": 425,
  "request_time": 0.892,
  "upstream_response_time": "0.891",
  "correlation_id": "req-uuid-abc123",
  "user_agent": "VaultFlow-SDK/1.0"
}
```

### Log Aggregation

Ship logs to a log aggregation system using Docker log drivers or a Kubernetes log agent:

```yaml
# Docker Compose: use Loki log driver
services:
  upload-service:
    logging:
      driver: loki
      options:
        loki-url: http://loki:3100/loki/api/v1/push
        labels: "service"
```

Or configure the Grafana Loki datasource and use LogQL:

```logql
# All errors from upload-service in last 15 minutes
{service="upload-service"} |= "ERROR" | json | level="ERROR"

# All requests for a specific correlation ID
{service=~".+"} |= "correlationId=abc-123"

# Upload throughput
rate({service="upload-service"} |= "Upload complete" [1m])
```

---

## Service Level Objectives

| SLO | Target | Prometheus Alert |
|---|---|---|
| Upload success rate | > 99.9% | `upload_error_rate > 0.001 for 5m` |
| Upload p99 latency | < 2 s | `upload_p99_latency > 2 for 5m` |
| Download p99 TTFB | < 500 ms | `download_p99_ttfb > 0.5 for 5m` |
| Auth p99 latency | < 300 ms | `auth_p99_latency > 0.3 for 5m` |
| Processing lag p95 | < 30 s | `processing_p95_duration > 30 for 5m` |
| Platform availability | > 99.95% | `service_up < 1 for 1m` |

---

## Health Endpoints

Each service exposes Spring Boot Actuator endpoints:

```
/actuator/health       — Overall service health (UP/DOWN)
/actuator/health/db    — PostgreSQL connectivity
/actuator/health/redis — Redis connectivity (if applicable)
/actuator/health/kafka — Kafka connectivity (if applicable)
/actuator/metrics      — Metric names list
/actuator/prometheus   — Prometheus scrape endpoint
/actuator/info         — Service version and environment info
```

Health check example:

```bash
curl http://localhost:8082/actuator/health | python3 -m json.tool
# {
#   "status": "UP",
#   "components": {
#     "db": { "status": "UP" },
#     "redis": { "status": "UP" },
#     "kafka": { "status": "UP" }
#   }
# }
```

---

## Capacity and Growth Monitoring

Run this query weekly to track storage growth:

```sql
SELECT
  DATE_TRUNC('week', created_at) AS week,
  SUM(size_bytes) / 1073741824.0 AS gb_uploaded,
  COUNT(*) AS files_uploaded,
  SUM(size_bytes) / COUNT(*) / 1048576.0 AS avg_size_mb
FROM object_versions
WHERE created_at > NOW() - INTERVAL '90 days'
  AND is_delete_marker = false
GROUP BY DATE_TRUNC('week', created_at)
ORDER BY week DESC;
```

Scale-out triggers:

| Metric | Threshold | Action |
|---|---|---|
| Upload-service CPU | > 60% avg 5 min | HPA adds pods |
| Download-service CPU | > 60% avg 5 min | HPA adds pods |
| Processing consumer lag | > 5,000 messages | Scale processing-service |
| PostgreSQL connections | > 80% pool | Increase pool / add read replica |
| Redis memory | > 70% | Increase maxmemory / Redis cluster |
| Object storage disk | > 75% | Provision additional storage |
