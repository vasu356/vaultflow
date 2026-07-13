# Troubleshooting Guide

This guide covers the most common issues encountered when running VaultFlow locally and in production.

For production incidents, see the [Operations Runbook](docs/RUNBOOK.md).

---

## Table of Contents

- [Startup Issues](#startup-issues)
- [Authentication Errors](#authentication-errors)
- [Upload Failures](#upload-failures)
- [Download Failures](#download-failures)
- [Processing Pipeline Issues](#processing-pipeline-issues)
- [Database Issues](#database-issues)
- [Kafka Issues](#kafka-issues)
- [Redis Issues](#redis-issues)
- [Performance Issues](#performance-issues)
- [Docker and Infrastructure Issues](#docker-and-infrastructure-issues)

---

## Startup Issues

### Service fails to start: "Connection refused" to PostgreSQL

**Symptom**: `Connection refused: postgres:5432` in service logs shortly after `docker-compose up`.

**Cause**: Service started before PostgreSQL was fully ready. Docker Compose health checks should prevent this, but a slow machine may need more time.

**Fix**:
```bash
# Check PostgreSQL health
docker-compose ps postgres
# Should show "healthy" — if "starting", wait and retry

# Check PostgreSQL logs for errors
docker-compose logs postgres | tail -30

# Force restart in correct order
docker-compose stop
docker-compose up -d postgres redis
sleep 15
docker-compose up -d
```

### Service fails to start: "Flyway migration failed"

**Symptom**: `FlywayException: Validate failed` or `Migration checksum mismatch` in logs.

**Cause**: Usually occurs when a migration script was modified after it had already been applied to the database.

**Fix (development only — do not do this in production)**:
```bash
# Wipe the database volume and start fresh
docker-compose down -v
docker-compose up -d
```

If this is a production issue, consult the DBA — never drop a production database to fix a Flyway checksum.

### JWT validation fails: "Could not read RSA public key"

**Symptom**: Services fail to start with `IllegalArgumentException: Could not read RSA public key from /keys/public.pem`.

**Cause**: The `keys/` directory does not exist or the key files were not generated.

**Fix**:
```bash
mkdir -p keys
openssl genrsa -out keys/private.pem 2048
openssl rsa -in keys/private.pem -pubout -out keys/public.pem

# Verify the files exist and are readable
cat keys/public.pem | head -1
# Should print: -----BEGIN PUBLIC KEY-----
```

---

## Authentication Errors

### HTTP 401 on every request even with a valid token

**Symptom**: Requests to upload-service, download-service, or admin-service return `401 Unauthorized` even immediately after login.

**Most likely cause**: JWT key mismatch. Each service generates its own RSA key pair if no key file is provided. A token signed by auth-service's key will not validate in upload-service's key.

**Fix**: Ensure all services mount the same key files. In `docker-compose.yml`, verify:

```yaml
# All services should have this volume mount:
volumes:
  - ./keys:/keys:ro

# And these environment variables:
environment:
  VAULTFLOW_JWT_PUBLIC_KEY_PATH: /keys/public.pem

# auth-service also needs:
environment:
  VAULTFLOW_JWT_PRIVATE_KEY_PATH: /keys/private.pem
```

After updating, restart all services: `docker-compose restart`.

### HTTP 401: "JWT token has expired"

**Cause**: The access token TTL is 15 minutes. The client must refresh the token before it expires.

**Fix**:
```bash
# Refresh the token
NEW_TOKENS=$(curl -s -X POST http://localhost:80/api/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\": \"$REFRESH_TOKEN\"}")

export TOKEN=$(echo $NEW_TOKENS | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")
export REFRESH_TOKEN=$(echo $NEW_TOKENS | python3 -c "import sys,json; print(json.load(sys.stdin)['refreshToken'])")
```

### HTTP 401: "Account locked"

**Cause**: 5 consecutive failed login attempts lock the account until `locked_until` timestamp.

**Fix (admin)**:
```sql
-- Unlock the account (connect to vaultflow database)
UPDATE users 
SET failed_login_count = 0, locked_until = NULL 
WHERE email = 'user@example.com';
```

Or via admin API:
```bash
curl -X POST http://localhost:80/api/v1/admin/users/{userId}/unlock \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### HTTP 429: Too Many Requests on login endpoint

**Cause**: NGINX rate limit on `/api/v1/auth/` is 10 req/s per IP with burst of 20.

**Fix for development**: The rate limit is appropriate for production. For load testing login, override the limit in `nginx.conf` or bypass NGINX and hit auth-service directly on port 8081.

---

## Upload Failures

### HTTP 413: Request Entity Too Large

**Cause**: File exceeds configured size limit.

- Single-part upload limit: 100 MB (`MAX_SINGLE_PART_SIZE`)
- NGINX `client_max_body_size`: 5 GB

**Fix for large files**: Use multipart upload for files > 100 MB.

```bash
# Initiate multipart upload
curl -X POST http://localhost:80/api/v1/uploads/initiate \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"bucketId": "...", "objectKey": "large-file.zip", "totalParts": 5}'
```

### HTTP 409: "Upload completion already in progress"

**Cause**: Two `POST /uploads/{id}/complete` requests raced. The Redis distributed lock prevented double-completion.

**Fix**: The second request failed safely. The first completed successfully. Check the upload status:
```bash
curl http://localhost:80/api/v1/uploads/$SESSION_ID/status \
  -H "Authorization: Bearer $TOKEN"
```

If status is `COMPLETED`, the upload succeeded. If `UPLOADING`, the first completion failed — wait 60 seconds for the lock to expire and retry.

### HTTP 409: "Conflict" on bucket creation

**Cause**: A bucket with that name already exists in your organization.

**Fix**: Choose a different bucket name or list existing buckets:
```bash
curl http://localhost:80/api/v1/buckets \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

### HTTP 507: "Quota exceeded"

**Cause**: Organization has reached its storage quota (default 100 GB).

**Fix via admin**:
```bash
curl -X PUT http://localhost:80/api/v1/admin/quota \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"orgId": "...", "quotaBytes": 214748364800}'  # 200 GB
```

### Checksum verification failure (HTTP 400)

**Symptom**: `{"errorCode": "CHECKSUM_MISMATCH"}` response.

**Cause**: The `X-Checksum-SHA256` header value did not match the actual file checksum. Either the file was corrupted in transit or the checksum was computed incorrectly.

**Fix**: Recompute the checksum before sending:
```bash
SHA=$(sha256sum your-file.pdf | awk '{print $1}')
curl -X PUT "http://localhost:80/api/v1/buckets/$BUCKET_ID/objects/file.pdf" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Checksum-SHA256: $SHA" \
  -H "Content-Type: application/pdf" \
  --data-binary @your-file.pdf
```

---

## Download Failures

### HTTP 403: "Object is infected — download blocked"

**Cause**: The virus scanner marked this object as infected. The object was uploaded and stored, but downloads are blocked.

**Fix (admin)**: Review the object in the audit log. If the result is a false positive, an admin must manually override `virus_scan_status` in the database:
```sql
-- Only do this after verifying the file is safe
UPDATE object_versions 
SET virus_scan_status = 'CLEAN' 
WHERE id = 'version-uuid';
```

### HTTP 404 on download immediately after upload

**Cause**: The object was successfully uploaded, but the download-service and upload-service are separate services. This can happen if:

1. You're calling the wrong port (upload: 8082, download: 8083, gateway: 80)
2. The bucket or object key has a typo

**Fix**: Always use the API gateway (`localhost:80`) to avoid port confusion. Verify the object was created:
```bash
# Check via admin service
curl http://localhost:80/api/v1/admin/overview \
  -H "Authorization: Bearer $TOKEN"
```

### Range request returns full file instead of partial content

**Cause**: Some clients send malformed `Range` headers. VaultFlow expects RFC 7233 format.

**Correct format**:
```bash
# First 1 MB
curl -H "Range: bytes=0-1048575" \
  -H "Authorization: Bearer $TOKEN" \
  http://localhost:80/api/v1/buckets/$BUCKET_ID/objects/file.mp4

# From byte 1048576 to end
curl -H "Range: bytes=1048576-" \
  -H "Authorization: Bearer $TOKEN" \
  http://localhost:80/api/v1/buckets/$BUCKET_ID/objects/file.mp4
```

---

## Processing Pipeline Issues

### Processing status stays "PENDING" indefinitely

**Cause**: The processing-service is not consuming from the `file.uploaded` topic. Possible causes:
1. processing-service is down or unhealthy
2. Kafka is not reachable
3. The `file.uploaded` topic was not created

**Diagnosis**:
```bash
# Check processing-service health
curl http://localhost:8085/actuator/health

# Check Kafka consumer group lag
docker exec vaultflow-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe \
  --group processing-service

# Check if topic exists
docker exec vaultflow-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --list | grep file.uploaded
```

**Fix**:
```bash
# Restart processing-service
docker-compose restart processing-service

# If topic is missing, recreate it
docker-compose restart kafka-init
```

### Processing failed — file in DLT

**Cause**: A file could not be processed after retries and was sent to `file.uploaded.DLT`.

**View DLT messages**:
```bash
docker exec vaultflow-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic file.uploaded.DLT \
  --from-beginning \
  --max-messages 10
```

**Replay**: After fixing the root cause (e.g., insufficient disk space for thumbnail generation), replay the DLT message to the main topic for reprocessing.

---

## Database Issues

### "Too many connections" error

**Cause**: HikariCP connection pool is exhausted. Default pool size is 10 per service. With 7 services × 10 connections = 70 connections; PostgreSQL default `max_connections` is 100.

**Check**:
```bash
docker exec vaultflow-postgres psql -U vaultflow -c \
  "SELECT count(*), state FROM pg_stat_activity GROUP BY state;"
```

**Fix (temporary)**:
```bash
# Increase pool size for the affected service
docker-compose stop upload-service
# Edit docker-compose.yml: add SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=20
docker-compose up -d upload-service
```

**Fix (permanent)**: Configure PgBouncer as a connection pooler in front of PostgreSQL.

### Flyway error: "Table ... already exists"

**Cause**: A migration was partially applied or rolled back incorrectly.

**Fix (development only)**:
```bash
# Check which migrations were applied
docker exec vaultflow-postgres psql -U vaultflow -c \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"

# For failed migration, mark it as repaired (use with caution)
# Then re-run the migration
```

---

## Kafka Issues

### "Leader not available" on topic creation

**Cause**: Kafka broker is still starting up when `kafka-init` tries to create topics.

**Fix**: The `kafka-init` container has a built-in retry loop (30 retries × 5 seconds). If it fails:
```bash
docker-compose restart kafka-init
docker-compose logs kafka-init
```

### Consumer group offset resets unexpectedly

**Cause**: Consumer group `__consumer_offsets` topic was deleted, or `auto.offset.reset=earliest` was triggered.

**Prevent**: Ensure `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"` (set in docker-compose.yml) so consumers do not accidentally create topics with wrong configurations.

---

## Redis Issues

### Redis eviction causes upload session loss

**Symptom**: `ResourceNotFoundException: UploadSession not found` during an in-progress multipart upload.

**Cause**: Redis is configured with `maxmemory-policy allkeys-lru`. Under high memory pressure, Redis may evict upload session keys.

**Prevention**:
- Set `maxmemory` high enough to hold all active upload sessions
- In production, use `maxmemory-policy volatile-lru` (only evict keys with TTL set)
- Upload session data is also persisted in PostgreSQL as a backup; the Redis key is for hot-path performance

**Recovery**: If a session is evicted, it can be reconstructed from PostgreSQL:
```sql
SELECT s.*, p.part_number, p.size_bytes 
FROM upload_sessions s
LEFT JOIN upload_parts p ON p.session_id = s.id
WHERE s.id = 'session-uuid';
```

---

## Performance Issues

### Upload throughput lower than expected

**Check**:
1. Is NGINX configured with `proxy_request_buffering off`? Without this, NGINX buffers the entire upload before forwarding, causing memory pressure and throughput degradation.
2. Is the storage volume on a fast disk? HDD storage will bottleneck on sequential writes.
3. Is the JVM heap sized appropriately? `upload-service` needs at least 512 MB for large in-memory operations.

**Verify NGINX streaming**:
```bash
# NGINX access log should show incremental bytes_sent during large upload
docker logs vaultflow-nginx -f
```

### High JVM GC pressure during large uploads

**Cause**: `IOUtils.toByteArray(stream)` loads the entire file into heap for checksum computation and content-type detection.

**Mitigation**: Increase the upload-service heap:
```yaml
# docker-compose.yml
environment:
  JAVA_OPTS: "-Xmx1536m -Xms256m -XX:+UseZGC"
```

For files > 500 MB, clients should use multipart upload where each part is ≤ 500 MB.

---

## Docker and Infrastructure Issues

### Port already in use

VaultFlow uses non-standard ports to avoid conflicts. If you still have conflicts:

```bash
# Find what's using the port
lsof -i :8081

# Change the port in docker-compose.yml:
ports:
  - "18081:8081"  # Changed external port
```

### Docker volume disk full

```bash
# Check volume sizes
docker system df -v

# List VaultFlow volumes
docker volume ls | grep vaultflow

# Remove object storage (DESTROYS all uploaded files)
docker-compose down
docker volume rm vaultflow_vaultflow-object-storage
docker-compose up -d
```

### "No space left on device" during Docker build

```bash
# Clean up unused Docker resources
docker system prune -f
docker builder prune -f
```

---

## Getting More Help

1. Check [FAQ.md](FAQ.md) for common questions
2. Search [GitHub Issues](https://github.com/your-org/vaultflow/issues)
3. Open a [GitHub Discussion](https://github.com/your-org/vaultflow/discussions) for questions
4. For production incidents, follow the [Operations Runbook](docs/RUNBOOK.md)
