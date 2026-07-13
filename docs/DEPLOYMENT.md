# Deployment Guide

This guide covers deploying VaultFlow in production environments. For local development, see [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md).

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Docker Compose (Single Host)](#docker-compose-single-host)
- [Kubernetes](#kubernetes)
- [Environment Configuration](#environment-configuration)
- [JWT Key Management](#jwt-key-management)
- [TLS / HTTPS Configuration](#tls--https-configuration)
- [Database Setup](#database-setup)
- [Storage Configuration](#storage-configuration)
- [Monitoring Setup](#monitoring-setup)
- [Health Checks](#health-checks)
- [CI/CD Pipeline](#cicd-pipeline)
- [Post-Deployment Verification](#post-deployment-verification)

---

## Prerequisites

### Production Checklist

Before deploying to production, verify:

- [ ] RSA key pair generated (4096-bit for production)
- [ ] All secrets stored in Kubernetes Secrets / Vault (not environment variables in code)
- [ ] `DB_PASSWORD` is not the development default (`vaultflow`)
- [ ] `REDIS_PASSWORD` is set
- [ ] `SIGNED_URL_SECRET` has ≥ 256 bits of entropy
- [ ] TLS certificates configured in NGINX
- [ ] Flyway migrations tested against production schema
- [ ] Monitoring and alerting configured
- [ ] Backup strategy implemented
- [ ] Disaster recovery runbook reviewed

---

## Docker Compose (Single Host)

Docker Compose is suitable for small deployments (< 50 GB storage, < 100 concurrent users).

### Setup

```bash
git clone https://github.com/your-org/vaultflow.git
cd vaultflow

# Generate production RSA key pair
mkdir -p keys
openssl genrsa -out keys/private.pem 4096
openssl rsa -in keys/private.pem -pubout -out keys/public.pem
chmod 600 keys/private.pem

# Create production environment file
cat > .env << 'EOF'
DB_PASSWORD=<strong-random-password>
REDIS_PASSWORD=<strong-random-password>
SIGNED_URL_SECRET=<256-bit-hex-string>
ENVIRONMENT=production
EOF

# Build images
docker-compose build

# Start
docker-compose up -d

# Verify
docker-compose ps
```

### Production Overrides

Create a `docker-compose.prod.yml` to override development defaults:

```yaml
# docker-compose.prod.yml
services:
  postgres:
    environment:
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    # Remove port exposure — internal only
    ports: []

  redis:
    command: redis-server --requirepass ${REDIS_PASSWORD} --maxmemory 1gb --maxmemory-policy volatile-lru
    ports: []

  auth-service:
    environment:
      DB_PASSWORD: ${DB_PASSWORD}
      REDIS_PASSWORD: ${REDIS_PASSWORD}
      ENVIRONMENT: production
      JAVA_OPTS: "-Xmx1g -Xms256m -XX:+UseZGC -XX:ZUncommitDelay=300"
```

```bash
docker-compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

---

## Kubernetes

Kubernetes is the recommended deployment target for production workloads requiring horizontal scaling, rolling updates, and high availability.

### Namespace and Configuration

```bash
# Create namespace
kubectl create namespace vaultflow

# Apply shared configuration and secrets
kubectl apply -f infrastructure/kubernetes/common/

# Create secrets from template (edit secrets-template.yaml first)
cp infrastructure/kubernetes/common/secrets-template.yaml secrets.yaml
# Edit secrets.yaml with production values (base64-encoded)
kubectl apply -f secrets.yaml
rm secrets.yaml  # Never commit secrets to git
```

### Secrets Setup

```bash
# Create JWT key secret
kubectl create secret generic vaultflow-jwt-keys \
  --from-file=private.pem=./keys/private.pem \
  --from-file=public.pem=./keys/public.pem \
  --namespace vaultflow

# Create application secrets
kubectl create secret generic vaultflow-secrets \
  --from-literal=DB_PASSWORD=<password> \
  --from-literal=REDIS_PASSWORD=<password> \
  --from-literal=SIGNED_URL_SECRET=<secret> \
  --namespace vaultflow
```

### Deploy Services

```bash
# Deploy in dependency order
kubectl apply -f infrastructure/kubernetes/common/
kubectl apply -f infrastructure/kubernetes/auth/
kubectl apply -f infrastructure/kubernetes/upload/
kubectl apply -f infrastructure/kubernetes/download/

# Wait for each service to be ready
kubectl rollout status deployment/auth-service -n vaultflow --timeout=300s
kubectl rollout status deployment/upload-service -n vaultflow --timeout=300s
kubectl rollout status deployment/download-service -n vaultflow --timeout=300s

# Verify pods
kubectl get pods -n vaultflow
```

### Ingress

Apply the ingress resource:

```bash
kubectl apply -f infrastructure/kubernetes/common/ingress.yaml
```

The ingress routes:
- `/api/v1/auth/*` → auth-service
- `/api/v1/buckets/*` → upload-service (PUT/DELETE) / download-service (GET)
- `/api/v1/uploads/*` → upload-service
- `/api/v1/download/*` → download-service
- `/api/v1/admin/*` → admin-service
- `/api/v1/search*` → metadata-service

### Scaling

```bash
# Manual scale
kubectl scale deployment/upload-service --replicas=5 -n vaultflow

# HPA is configured in the deployment manifests
# Scales between 2–20 replicas based on CPU (target 60%)
kubectl get hpa -n vaultflow
```

### Rolling Update

```bash
# Update image tag
IMAGE_TAG="sha-$(git rev-parse --short HEAD)"

kubectl set image deployment/auth-service \
  auth-service=ghcr.io/your-org/vaultflow/auth-service:${IMAGE_TAG} \
  -n vaultflow

kubectl rollout status deployment/auth-service -n vaultflow --timeout=300s
```

### Rollback

```bash
# Rollback to previous revision
kubectl rollout undo deployment/auth-service -n vaultflow

# Rollback all services
for svc in auth-service upload-service download-service processing-service notification-service admin-service; do
  kubectl rollout undo deployment/$svc -n vaultflow
done
```

---

## Environment Configuration

### Required Variables (All Services)

| Variable | Production Value |
|---|---|
| `DB_URL` | `jdbc:postgresql://<host>:5432/vaultflow` |
| `DB_USERNAME` | `vaultflow` |
| `DB_PASSWORD` | Strong random password (Kubernetes Secret) |
| `REDIS_HOST` | Redis hostname |
| `REDIS_PASSWORD` | Strong random password (Kubernetes Secret) |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker addresses (comma-separated for HA) |
| `ENVIRONMENT` | `production` |
| `VAULTFLOW_JWT_PUBLIC_KEY_PATH` | `/keys/public.pem` (Kubernetes Secret mount) |

### auth-service Only

| Variable | Production Value |
|---|---|
| `VAULTFLOW_JWT_PRIVATE_KEY_PATH` | `/keys/private.pem` (Kubernetes Secret mount) |

### download-service

| Variable | Production Value |
|---|---|
| `SIGNED_URL_SECRET` | 256-bit hex string (rotate quarterly) |
| `DOWNLOAD_BASE_URL` | `https://api.yourdomain.com` |
| `STORAGE_BASE_DIR` | `/data/vaultflow/objects` (shared PVC) |

---

## JWT Key Management

### Key Generation

```bash
# Production: use 4096-bit RSA
openssl genrsa -out private.pem 4096
openssl rsa -in private.pem -pubout -out public.pem

# Verify
openssl rsa -in private.pem -check -noout
```

### Key Rotation Procedure

Key rotation is a manual process. Plan for a 15-minute window (access token TTL):

1. Generate new key pair
2. Add new public key to auth-service JWKS endpoint (edit to serve both keys)
3. Deploy auth-service with new private key (rolling update — no downtime)
4. Wait 15 minutes for all existing access tokens to expire
5. Remove old public key from JWKS endpoint
6. Update Kubernetes Secret with new key files

---

## TLS / HTTPS Configuration

Edit `infrastructure/nginx/nginx.conf` to add TLS:

```nginx
server {
    listen 80;
    server_name api.yourdomain.com;
    return 301 https://$server_name$request_uri;  # HTTP → HTTPS redirect
}

server {
    listen 443 ssl http2;
    server_name api.yourdomain.com;

    ssl_certificate /etc/nginx/ssl/fullchain.pem;
    ssl_certificate_key /etc/nginx/ssl/privkey.pem;

    # Mozilla Intermediate compatibility
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384;
    ssl_prefer_server_ciphers off;
    ssl_session_timeout 1d;
    ssl_session_cache shared:SSL:10m;
    ssl_stapling on;
    ssl_stapling_verify on;

    # ... rest of server block
}
```

For Let's Encrypt certificates with Certbot:

```bash
# Install certbot
apt install certbot python3-certbot-nginx

# Obtain certificate
certbot --nginx -d api.yourdomain.com

# Auto-renewal (certbot adds this automatically)
systemctl enable certbot.timer
```

---

## Database Setup

### Initial Setup

PostgreSQL is initialized with `infrastructure/docker/postgres-init.sql`. Flyway migrations (`V1__core_schema.sql`, `V2__storage_schema.sql`, `V3__optimistic_locking.sql`) are applied automatically on service startup.

### Connection Pooling

For production, place PgBouncer in front of PostgreSQL:

```ini
# pgbouncer.ini
[databases]
vaultflow = host=postgres port=5432 dbname=vaultflow

[pgbouncer]
listen_addr = 0.0.0.0
listen_port = 6432
pool_mode = transaction
max_client_conn = 1000
default_pool_size = 25
```

Update `DB_URL` to point to PgBouncer: `jdbc:postgresql://pgbouncer:6432/vaultflow`.

### Backup

```bash
# Manual backup
pg_dump -U vaultflow -h <host> vaultflow | gzip > vaultflow-$(date +%Y%m%d).sql.gz

# Restore
zcat vaultflow-20241015.sql.gz | psql -U vaultflow -h <host> vaultflow
```

For continuous backup, configure WAL archiving to S3/GCS.

### Monthly Audit Log Partition

Create partitions for upcoming months (automate with pg_partman in production):

```sql
-- Run at start of each month
DO $$
DECLARE
  next_month DATE := DATE_TRUNC('month', NOW() + INTERVAL '1 month');
  partition_name TEXT := 'audit_logs_' || TO_CHAR(next_month, 'YYYY_MM');
BEGIN
  EXECUTE format(
    'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_logs
     FOR VALUES FROM (%L) TO (%L)',
    partition_name, next_month, next_month + INTERVAL '1 month'
  );
END $$;
```

---

## Storage Configuration

### Local Filesystem (Single Host)

```yaml
STORAGE_BASE_DIR: /data/vaultflow/objects
```

Ensure the volume is backed up regularly and has sufficient capacity for projected growth.

### Shared NFS (Multi-Host)

For Kubernetes with multiple replicas, use a shared NFS/EFS volume:

```yaml
# kubernetes PVC
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: vaultflow-object-storage
  namespace: vaultflow
spec:
  accessModes:
    - ReadWriteMany  # Required for multi-replica access
  storageClassName: efs-sc  # AWS EFS or NFS provisioner
  resources:
    requests:
      storage: 500Gi
```

### S3 (Recommended for Production)

When the S3 adapter is implemented (see [ROADMAP](../ROADMAP.md)):

```yaml
STORAGE_ADAPTER: s3
AWS_S3_BUCKET: vaultflow-objects-prod
AWS_REGION: us-east-1
# Use IAM role for credentials (no access keys needed on EKS)
```

---

## Monitoring Setup

### Prometheus Scraping

Add VaultFlow services to your Prometheus configuration or use the provided `infrastructure/monitoring/prometheus/prometheus.yml`.

In production with Kubernetes, use the Prometheus Operator and `ServiceMonitor` CRDs:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: vaultflow
  namespace: vaultflow
spec:
  selector:
    matchLabels:
      app.kubernetes.io/part-of: vaultflow
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 15s
```

### Grafana Dashboards

Import the pre-built dashboards from `infrastructure/monitoring/grafana/dashboards/vaultflow-overview.json`.

### Alerting

Recommended Prometheus alert rules:

```yaml
# alerts.yml
groups:
  - name: vaultflow
    rules:
      - alert: HighUploadErrorRate
        expr: rate(http_server_requests_total{service="upload-service",status=~"5.."}[5m]) > 0.001
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Upload error rate above threshold"

      - alert: ProcessingLagHigh
        expr: kafka_consumer_lag_sum{group="processing-service"} > 5000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Processing pipeline consumer lag is high"

      - alert: DiskSpaceLow
        expr: (node_filesystem_avail_bytes{mountpoint="/data"} / node_filesystem_size_bytes{mountpoint="/data"}) < 0.20
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Object storage disk space below 20%"
```

---

## Health Checks

```bash
# Check all services
for port in 8081 8082 8083 8084 8085 8086 8087; do
  STATUS=$(curl -sf http://localhost:$port/actuator/health \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','UNKNOWN'))" 2>/dev/null \
    || echo "UNREACHABLE")
  echo "Port $port: $STATUS"
done

# Kubernetes
kubectl get pods -n vaultflow
kubectl describe pod <pod-name> -n vaultflow  # for failing pods
```

---

## Post-Deployment Verification

Run after every deployment to production:

```bash
#!/bin/bash
BASE=https://api.yourdomain.com

# 1. Health check
curl -sf $BASE/health || { echo "FAIL: gateway health"; exit 1; }

# 2. Auth service
curl -sf $BASE/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"organizationName":"smoke-test","organizationSlug":"smoke-test-'$(date +%s)'","fullName":"Smoke Test","email":"smoke@example.com","password":"SmokeTest1!"}' \
  || { echo "FAIL: register"; exit 1; }

echo "All smoke tests passed"
```

Full smoke test script: See `docs/RUNBOOK.md §3` for the complete post-deployment verification procedure.
