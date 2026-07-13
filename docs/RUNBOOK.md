# VaultFlow Operations Runbook

## On-Call Quick Reference

| Symptom                          | First Check                              | Runbook Section   |
|----------------------------------|------------------------------------------|-------------------|
| Upload failures                  | upload-service logs, Redis quota cache   | §3.1              |
| Download 500 errors              | download-service logs, storage mount     | §3.2              |
| Processing pipeline backed up    | Kafka consumer lag, processing-service   | §3.3              |
| Login failures spike             | auth-service logs, Redis blacklist       | §3.4              |
| DB connection pool exhausted     | Hikari metrics, slow query log           | §3.5              |
| High memory (JVM OOM risk)       | JVM heap metrics, Grafana dashboard      | §3.6              |

---

## §1. Health Checks

```bash
# Check all service health
for port in 8081 8082 8083 8085 8086 8087; do
  echo "Port $port: $(curl -sf http://localhost:$port/actuator/health | jq -r .status)"
done

# Kubernetes pod status
kubectl get pods -n vaultflow
kubectl describe pod <pod-name> -n vaultflow

# Service logs (last 100 lines)
kubectl logs -n vaultflow deployment/upload-service --tail=100

# PostgreSQL connectivity
kubectl exec -n vaultflow-infra deploy/postgres -- pg_isready -U vaultflow

# Redis connectivity
kubectl exec -n vaultflow-infra deploy/redis -- redis-cli ping

# Kafka topic health
kubectl exec -n vaultflow-infra deploy/kafka -- \
  kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group processing-service
```

---

## §2. Common Operational Tasks

### 2.1 Rolling Restart (zero-downtime)

```bash
kubectl rollout restart deployment/upload-service -n vaultflow
kubectl rollout status deployment/upload-service -n vaultflow --timeout=300s
```

### 2.2 Scale a Service

```bash
# Scale upload-service to 10 replicas
kubectl scale deployment/upload-service --replicas=10 -n vaultflow

# Verify
kubectl get deployment upload-service -n vaultflow
```

### 2.3 Emergency Rollback

```bash
# Rollback to previous revision
kubectl rollout undo deployment/upload-service -n vaultflow

# Rollback all services
for svc in auth-service upload-service download-service processing-service notification-service admin-service; do
  kubectl rollout undo deployment/$svc -n vaultflow
done
```

### 2.4 Force Expire Stale Upload Sessions

```bash
# Connect to DB and expire stale sessions
kubectl exec -n vaultflow-infra deploy/postgres -- psql -U vaultflow -c \
  "UPDATE upload_sessions SET status = 'EXPIRED'
   WHERE status IN ('INITIATED','UPLOADING')
   AND expires_at < NOW();"
```

### 2.5 Increase Org Quota (emergency)

```bash
# Via admin API
curl -X PUT http://localhost/api/v1/admin/quota \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"quotaBytes": 214748364800}'  # 200 GB
```

---

## §3. Incident Playbooks

### 3.1 Upload Failures Spike

**Alert**: `upload_error_rate > 0.001` for 5 minutes

```bash
# 1. Check upload-service error logs
kubectl logs -n vaultflow deployment/upload-service --tail=200 | grep ERROR

# 2. Check quota exhaustion
curl -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost/api/v1/admin/overview

# 3. Check storage disk space
kubectl exec -n vaultflow deployment/upload-service -- df -h /data/vaultflow/objects

# 4. Check DB connection pool
curl http://localhost:8082/actuator/metrics/hikaricp.connections.active

# 5. Check Redis for distributed lock stuck
kubectl exec -n vaultflow-infra deploy/redis -- redis-cli KEYS "lock:upload:*"
# Clear stuck locks older than 2 minutes:
# kubectl exec -n vaultflow-infra deploy/redis -- redis-cli DEL "lock:upload:<session-id>"
```

**Escalation**: If disk > 90%, immediately increase storage quota or provision additional storage.

### 3.2 Download Errors Spike

**Alert**: `download_error_rate > 0.001` for 5 minutes

```bash
# 1. Check if storage mount is healthy
kubectl exec -n vaultflow deployment/download-service -- ls /data/vaultflow/objects

# 2. Check DB for object version inconsistency
kubectl exec -n vaultflow-infra deploy/postgres -- psql -U vaultflow -c \
  "SELECT COUNT(*) FROM object_versions WHERE virus_scan_status = 'INFECTED';"

# 3. Check Redis signed URL cache
kubectl exec -n vaultflow-infra deploy/redis -- redis-cli KEYS "signedurl:valid:*" | wc -l
```

### 3.3 Processing Pipeline Backed Up

**Alert**: Kafka consumer lag for `processing-service` group > 10,000 messages

```bash
# 1. Check processing-service pod health
kubectl get pods -n vaultflow -l app=processing-service

# 2. View consumer lag
kubectl exec -n vaultflow-infra deploy/kafka -- \
  kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group processing-service

# 3. Scale up processing-service (more consumers)
kubectl scale deployment/processing-service --replicas=8 -n vaultflow
# Note: max useful replicas = number of partitions (16 for file.uploaded)

# 4. Check for processing failures in DLT
kubectl exec -n vaultflow-infra deploy/kafka -- \
  kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic file.uploaded.DLT --from-beginning --max-messages 10

# 5. If DLT has messages, inspect and replay:
# Save DLT messages to file, fix root cause, replay to original topic
```

### 3.4 Auth Failures Spike

**Alert**: `auth_login_failures_total` rate > 100/min

```bash
# 1. Check for brute-force pattern (many failures from same IP)
kubectl logs -n vaultflow deployment/auth-service --tail=500 | \
  grep "Failed login" | awk '{print $NF}' | sort | uniq -c | sort -rn | head -20

# 2. Check Redis blacklist size (may need pruning if too large)
kubectl exec -n vaultflow-infra deploy/redis -- redis-cli DBSIZE

# 3. Check for JWT parsing errors (possible key rotation issue)
kubectl logs -n vaultflow deployment/auth-service --tail=200 | grep "JWT"

# 4. If brute-force detected, temporarily tighten NGINX rate limit:
# Edit nginx.conf: change auth_limit zone from 10r/s to 2r/s
# kubectl apply -f infrastructure/nginx/nginx.conf
# kubectl rollout restart deployment/nginx -n vaultflow
```

### 3.5 Database Connection Pool Exhausted

**Alert**: `hikaricp_connections_active / hikaricp_connections_max > 0.95`

```bash
# 1. Identify which service is exhausted
curl http://localhost:8082/actuator/metrics/hikaricp.connections.active
curl http://localhost:8081/actuator/metrics/hikaricp.connections.active

# 2. Check for long-running queries
kubectl exec -n vaultflow-infra deploy/postgres -- psql -U vaultflow -c \
  "SELECT pid, now() - query_start AS duration, query, state
   FROM pg_stat_activity
   WHERE state != 'idle' AND query_start < NOW() - INTERVAL '30 seconds'
   ORDER BY duration DESC;"

# 3. Kill long-running query if safe (coordinate with team)
# psql -c "SELECT pg_terminate_backend(<pid>);"

# 4. Temporary fix: increase pool size via env var and rolling restart
kubectl set env deployment/upload-service \
  SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=40 -n vaultflow
```

### 3.6 JVM Heap OOM Risk

**Alert**: JVM heap usage > 90% for 10 minutes

```bash
# 1. Identify affected service
# Check Grafana: JVM Heap Usage by Service dashboard

# 2. Get heap dump for post-incident analysis (non-disruptive)
kubectl exec -n vaultflow <pod-name> -- \
  jcmd 1 VM.heap_info

# 3. Trigger GC (ZGC is concurrent — this forces a sync cycle)
kubectl exec -n vaultflow <pod-name> -- jcmd 1 GC.run

# 4. If not resolving, rolling restart
kubectl rollout restart deployment/<service-name> -n vaultflow

# 5. If OOM is happening frequently, increase memory limit
kubectl patch deployment upload-service -n vaultflow \
  --patch '{"spec":{"template":{"spec":{"containers":[{"name":"upload-service","resources":{"limits":{"memory":"3Gi"}}}]}}}}'
```

---

## §4. Database Maintenance

### 4.1 Create Next Month's Audit Log Partition (monthly task)

```bash
kubectl exec -n vaultflow-infra deploy/postgres -- psql -U vaultflow << 'EOF'
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
  RAISE NOTICE 'Created partition: %', partition_name;
END $$;
EOF
```

### 4.2 Archive Old Audit Log Partition

```bash
# Detach partition older than 12 months (instant, no data movement)
kubectl exec -n vaultflow-infra deploy/postgres -- psql -U vaultflow -c \
  "ALTER TABLE audit_logs DETACH PARTITION audit_logs_2023_01;"

# Export to cold storage (optional)
kubectl exec -n vaultflow-infra deploy/postgres -- \
  pg_dump -U vaultflow -t audit_logs_2023_01 vaultflow > audit_logs_2023_01.sql
```

### 4.3 VACUUM and ANALYZE (run if autovacuum missed)

```bash
kubectl exec -n vaultflow-infra deploy/postgres -- psql -U vaultflow -c \
  "VACUUM ANALYZE object_versions; VACUUM ANALYZE objects; VACUUM ANALYZE upload_sessions;"
```

---

## §5. Capacity Planning

### Storage growth projection

```sql
-- Run weekly to track growth rate
SELECT
  DATE_TRUNC('week', created_at) AS week,
  SUM(size_bytes) AS bytes_uploaded,
  COUNT(*) AS objects_uploaded,
  SUM(size_bytes) / COUNT(*) AS avg_object_size
FROM object_versions
WHERE created_at > NOW() - INTERVAL '90 days'
  AND is_delete_marker = false
GROUP BY DATE_TRUNC('week', created_at)
ORDER BY week DESC;
```

### Scale trigger thresholds

| Metric                        | Scale-out trigger    | Action                                  |
|-------------------------------|---------------------|-----------------------------------------|
| Upload-service CPU            | > 60% avg 5min      | HPA adds pods (auto)                    |
| Download-service CPU          | > 60% avg 5min      | HPA adds pods (auto)                    |
| Processing consumer lag       | > 5,000 messages    | Scale processing-service replicas       |
| PostgreSQL connections        | > 80% pool          | Increase pool size, add read replica    |
| Redis memory                  | > 70%               | Increase maxmemory, add Redis cluster   |
| Object storage disk           | > 75%               | Provision additional storage volume     |
| Kafka disk per broker         | > 70%               | Add broker or reduce retention          |
