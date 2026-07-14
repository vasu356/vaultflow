# Local Development Guide

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Docker Desktop | 24+ | https://docker.com/products/docker-desktop |
| Docker Compose | 2.20+ | bundled with Docker Desktop |
| Java | 21 | https://adoptium.net |
| Maven | 3.9+ | https://maven.apache.org |
| curl / httpie | any | for testing |

---

## Quickstart (Docker Compose — recommended)

```bash
git clone <repo>
cd vaultflow

# Build all service images
docker-compose build

# Start infrastructure first (PostgreSQL, Redis, Kafka, and topic init)
# kafka-init depends_on kafka with condition: service_healthy, so ordering is automatic
docker-compose up -d postgres redis zookeeper kafka kafka-init

# Wait for kafka-init to complete (it retries until Kafka is ready, then exits 0)
docker-compose wait kafka-init

# Start all application services
docker-compose up -d auth-service upload-service download-service \
  processing-service notification-service admin-service metadata-service

# Tail logs
docker-compose logs -f auth-service upload-service
```

Services are ready when healthchecks pass:
```bash
docker-compose ps   # all should show "healthy"
```

---

## Important: JWT Key Sharing in Local Dev

**The problem:** Each service generates its own RSA key pair in memory on startup
(see `JwtAutoConfiguration`). A token signed by auth-service's private key will
**not** validate in upload-service because they have different key pairs.

**The fix for Docker Compose:** Set a shared HMAC secret override.
Since RS256 private keys are complex to share, the simplest local-dev approach is
to configure all services to use a fixed symmetric key via environment variables.

**Production approach:** Auth-service exposes a JWKS endpoint. All other services
fetch the public key from it on startup. This is the correct long-term solution.

### Option A: Use the same shared secret (local dev only)

Add this to each service in `docker-compose.yml`:
```yaml
environment:
  VAULTFLOW_JWT_ISSUER: vaultflow-auth
  # All services share the same in-memory key — NOT production-safe
  # In production, use JWKS endpoint discovery instead
```

For truly shared keys in local dev, run **auth-service only** as the token issuer,
and configure other services to call auth-service's JWKS endpoint at startup.

### Option B: Run auth-service as the only JWT issuer (recommended)

In a single Docker Compose network, all services can call auth-service internally.
Add to `upload-service`, `download-service`, `admin-service`, `metadata-service`:

```yaml
environment:
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK-SET-URI: http://auth-service:8081/.well-known/jwks.json
```

Then add a JWKS endpoint to auth-service (see `AuthController.getJwks()` below).

### Option C: Test without cross-service auth (easiest)

Use the `auth-service` directly and its swagger at `:8081/swagger-ui.html`.
Only auth-service validates tokens properly in single-JVM dev mode.

---

## Running a Single Service Locally (JVM, not Docker)

Start infrastructure via Docker Compose:
```bash
docker-compose up -d postgres redis zookeeper kafka kafka-init
```

Run a service directly:
```bash
cd auth-service
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx512m"
```

Environment is pre-configured in `application.yml` to point at `localhost:5432` etc.

---

## Verifying the Stack

```bash
# Health check all services
for port in 8081 8082 8083 8084 8085 8086 8087; do
  status=$(curl -sf http://localhost:$port/actuator/health | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','DOWN'))" 2>/dev/null || echo "UNREACHABLE")
  echo "Port $port: $status"
done

# Open UIs
open http://localhost:8081/swagger-ui.html   # Auth service API docs
open http://localhost:3001                    # Grafana (admin/admin)
open http://localhost:9091                    # Prometheus
open http://localhost:16686                   # Jaeger tracing
```

---

## End-to-End Test Flow

```bash
BASE=http://localhost:8081

# 1. Register
RESPONSE=$(curl -s -X POST $BASE/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "organizationName":"Test Org",
    "organizationSlug":"test-org-'$(date +%s)'",
    "fullName":"Test User",
    "email":"test@example.com",
    "password":"Password1!"
  }')
echo $RESPONSE | python3 -m json.tool

TOKEN=$(echo $RESPONSE | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")
echo "Token: ${TOKEN:0:50}..."

# 2. Create bucket (via upload-service)
BUCKET=$(curl -s -X POST http://localhost:8082/api/v1/buckets \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"my-test-bucket"}')
echo $BUCKET | python3 -m json.tool
BUCKET_ID=$(echo $BUCKET | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

# 3. Upload a file
echo "Hello VaultFlow!" > /tmp/test-upload.txt
curl -v -X PUT \
  "http://localhost:8082/api/v1/buckets/$BUCKET_ID/objects/hello/world.txt" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: text/plain" \
  --data-binary @/tmp/test-upload.txt

# 4. Download the file
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8083/api/v1/buckets/$BUCKET_ID/objects/hello/world.txt"

# 5. Check admin overview
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8087/api/v1/admin/overview | python3 -m json.tool
```

---

## Running Tests

```bash
# Unit tests only (fast, no Docker needed)
mvn test

# Unit + integration tests (requires Docker for Testcontainers)
mvn verify

# Single service tests
cd auth-service && mvn test

# Watch integration test logs
mvn verify -pl auth-service -Dorg.testcontainers.reuse.enable=true
```

---

## Troubleshooting

### Service won't start — "Connection refused" to PostgreSQL
```bash
docker-compose ps postgres   # Should be "healthy"
docker-compose logs postgres | tail -20
# Fix: docker-compose restart postgres
```

### Kafka consumer lag is growing
```bash
docker exec vaultflow-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9094 \
  --describe --group processing-service
# Fix: docker-compose restart processing-service
```

### Upload returns 401 even with valid token
This is the JWT key sharing problem described above.
In Docker Compose mode, each service has its own RSA key.
Use auth-service (`localhost:8081`) for both login AND testing protected endpoints
(or configure JWKS endpoint sharing — see Option B above).

### Out of disk space (object storage)
```bash
docker volume ls | grep vaultflow
docker volume rm vaultflow_object-storage  # DESTROYS all uploaded files
docker-compose down -v && docker-compose up -d  # Fresh start
```

### Port conflict
```bash
lsof -i :8081   # Find what's using port 8081
# Change port in docker-compose.yml: "8181:8081"
```

---

## Stopping

```bash
docker-compose down          # Stop services, keep volumes (data preserved)
docker-compose down -v       # Stop AND destroy all volumes (clean slate)
```
