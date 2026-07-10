#!/usr/bin/env bash
# VaultFlow Startup Script
# Usage:
#   ./start.sh              start everything (infra + build + services)
#   ./start.sh stop         stop all containers
#   ./start.sh clean        stop + wipe all volumes (fresh start)
#   ./start.sh status       health check all 7 services
#   ./start.sh logs <svc>   tail logs, e.g: ./start.sh logs auth-service
#   ./start.sh infra        start only postgres/redis/kafka
#   ./start.sh apps         build + start only app services
set -euo pipefail

BLUE='\033[0;34m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${BLUE}>>>${NC} $*"; }
ok()   { echo -e "${GREEN}[OK]${NC} $*"; }
warn() { echo -e "${YELLOW}[!!]${NC} $*"; }
die()  { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

# Detect docker compose
if docker compose version &>/dev/null 2>&1; then DC="docker compose"
elif docker-compose version &>/dev/null 2>&1; then DC="docker-compose"
else die "Docker Compose not found. Install Docker Desktop."; fi
ok "Using: $DC"

CMD="${1:-start}"

case "$CMD" in
  stop)
    log "Stopping all containers..."; $DC down; ok "Stopped."; exit 0 ;;
  clean)
    warn "Deletes ALL data (database, uploads). Type 'yes' to confirm."
    printf "> "; read -r c; [ "$c" = "yes" ] || { echo "Cancelled."; exit 0; }
    $DC down -v --remove-orphans; ok "Clean."; exit 0 ;;
  logs)
    $DC logs -f --tail=300 "${2:-}"; exit 0 ;;
  status)
    echo ""; echo "=== Container Status ==="; $DC ps; echo ""
    echo "=== Service Health ==="
    for port in 8081 8082 8083 8084 8085 8086 8087; do
      case $port in
        8081) n="auth-service";;        8082) n="upload-service";;
        8083) n="download-service";;    8084) n="metadata-service";;
        8085) n="processing-service";; 8086) n="notification-service";;
        8087) n="admin-service";;
      esac
      body=$(curl -sf --max-time 3 "http://localhost:${port}/actuator/health" 2>/dev/null || true)
      if [ -z "$body" ]; then
        warn "  :${port}  ${n}  -> not responding"
      else
        st=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "?")
        [ "$st" = "UP" ] && ok "  :${port}  ${n}  -> UP" || warn "  :${port}  ${n}  -> ${st}"
      fi
    done; echo ""; exit 0 ;;
esac

# ── Start infrastructure ──────────────────────────────────────────────────
start_infra() {
  log "Starting infrastructure (postgres, redis, zookeeper, kafka)..."
  $DC up -d postgres redis zookeeper kafka

  log "Waiting for PostgreSQL..."
  for i in $(seq 1 30); do
    $DC exec -T postgres pg_isready -U vaultflow -d vaultflow >/dev/null 2>&1 && { ok "PostgreSQL ready"; break; }
    [ "$i" = "30" ] && die "PostgreSQL timed out"
    printf "."; sleep 3
  done

  log "Waiting for Redis..."
  for i in $(seq 1 20); do
    $DC exec -T redis redis-cli -a vaultflow ping 2>/dev/null | grep -q "PONG" && { ok "Redis ready"; break; }
    [ "$i" = "20" ] && die "Redis timed out"
    printf "."; sleep 2
  done

  log "Waiting 45s for Zookeeper + Kafka to initialize..."
  sleep 45

  log "Verifying Kafka..."
  for i in $(seq 1 12); do
    $DC exec -T kafka bash -c "kafka-topics --bootstrap-server localhost:9092 --list >/dev/null 2>&1" && { ok "Kafka ready"; break; }
    [ "$i" = "12" ] && { warn "Kafka slow, continuing anyway..."; break; }
    printf "."; sleep 5
  done

  log "Creating Kafka topics..."
  $DC up kafka-init
  ok "Kafka topics created"
}

# ── Build + start app services ────────────────────────────────────────────
start_apps() {
  log "Building Docker images..."
  log "(First build: ~15-20 min — Maven downloads deps inside Docker using JDK 21)"
  log "Watch progress: ./start.sh logs auth-service  (in another terminal)"
  $DC build \
    auth-service upload-service download-service \
    processing-service notification-service admin-service metadata-service

  log "Starting application services..."
  $DC up -d \
    auth-service upload-service download-service \
    processing-service notification-service admin-service metadata-service

  log "Waiting 90s for JVM startup + database migrations..."
  sleep 90
  bash "$0" status
}

# ── Main entry points ─────────────────────────────────────────────────────
if [ "$CMD" = "infra" ]; then start_infra; exit 0; fi
if [ "$CMD" = "apps" ];  then start_apps;  exit 0; fi

# Default: start everything
start_infra
start_apps

echo ""
echo -e "${GREEN}══════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  VaultFlow is running!${NC}"
echo -e "${GREEN}══════════════════════════════════════════════════${NC}"
echo ""
echo "  Swagger (auth):   http://localhost:8081/swagger-ui.html"
echo "  Swagger (upload): http://localhost:8082/swagger-ui.html"
echo "  Grafana:          http://localhost:3001  (admin/admin)"
echo "  Jaeger:           http://localhost:16686"
echo "  Prometheus:       http://localhost:9091"
echo ""
echo "  PostgreSQL:  localhost:5433  user/pass/db: vaultflow"
echo "  Redis:       localhost:6380  password: vaultflow"
echo "  Kafka:       localhost:9094"
echo ""
echo "  Quick test (register + get token):"
echo ""
echo "    curl -s -X POST http://localhost:8081/api/v1/auth/register \\"
echo "      -H 'Content-Type: application/json' \\"
SLUG="acme-$(date +%s)"
echo "      -d '{\"organizationName\":\"Acme\",\"organizationSlug\":\"${SLUG}\",\"fullName\":\"Alice\",\"email\":\"alice@acme.com\",\"password\":\"Password1!\"}' \\"
echo "      | python3 -m json.tool"
echo ""
echo "  Commands:"
echo "    ./start.sh status           re-check health"
echo "    ./start.sh logs auth-service  view a service's logs"
echo "    ./start.sh stop             stop everything"
echo "    ./start.sh clean            wipe + reset"
