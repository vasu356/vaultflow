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

# ── Detect docker compose ─────────────────────────────────────────────────
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

# ── Pre-flight checks ─────────────────────────────────────────────────────
preflight() {
  # 1. Ensure .env exists
  if [ ! -f ".env" ]; then
    warn ".env not found. Creating from .env.example..."
    if [ ! -f ".env.example" ]; then
      die ".env.example not found. Please re-clone the repository."
    fi
    cp .env.example .env
    echo ""
    warn "════════════════════════════════════════════════════════"
    warn "  ACTION REQUIRED: Fill in .env before continuing."
    warn ""
    warn "  Minimum required values:"
    warn "    DB_PASSWORD      — any strong random string"
    warn "    REDIS_PASSWORD   — any strong random string"
    warn "    SIGNED_URL_SECRET — any strong random string"
    warn "    GRAFANA_ADMIN_PASSWORD — any password for Grafana UI"
    warn ""
    warn "  Quick setup (generates random values):"
    warn "    DB_PASSWORD=\$(openssl rand -hex 16)"
    warn "    REDIS_PASSWORD=\$(openssl rand -hex 16)"
    warn "    SIGNED_URL_SECRET=\$(openssl rand -hex 32)"
    warn "    GRAFANA_ADMIN_PASSWORD=\$(openssl rand -hex 12)"
    warn ""
    warn "  Write these to .env and re-run ./start.sh"
    warn "════════════════════════════════════════════════════════"
    exit 1
  fi

  # Source .env to check required variables
  set -a; source .env; set +a

  local missing=()
  [ -z "${DB_PASSWORD:-}" ]           && missing+=("DB_PASSWORD")
  [ -z "${REDIS_PASSWORD:-}" ]        && missing+=("REDIS_PASSWORD")
  [ -z "${SIGNED_URL_SECRET:-}" ]     && missing+=("SIGNED_URL_SECRET")
  [ -z "${GRAFANA_ADMIN_PASSWORD:-}" ] && missing+=("GRAFANA_ADMIN_PASSWORD")

  if [ ${#missing[@]} -gt 0 ]; then
    die "Missing required values in .env: ${missing[*]}"
  fi

  # 2. Ensure RSA keys exist
  if [ ! -f "keys/private.pem" ] || [ ! -f "keys/public.pem" ]; then
    log "RSA key pair not found. Generating..."
    mkdir -p keys
    openssl genrsa -out keys/private.pem 2048 2>/dev/null
    openssl rsa -in keys/private.pem -pubout -out keys/public.pem 2>/dev/null
    chmod 600 keys/private.pem
    ok "RSA key pair generated in keys/"
  else
    ok "RSA key pair found"
  fi
}

# ── Start infrastructure ──────────────────────────────────────────────────
start_infra() {
  log "Starting infrastructure (postgres, redis, zookeeper, kafka)..."
  $DC up -d postgres redis zookeeper kafka

  log "Waiting for PostgreSQL..."
  for i in $(seq 1 30); do
    $DC exec -T postgres pg_isready -U "${DB_USERNAME:-vaultflow}" -d vaultflow >/dev/null 2>&1 && { ok "PostgreSQL ready"; break; }
    [ "$i" = "30" ] && die "PostgreSQL timed out"
    printf "."; sleep 3
  done

  log "Waiting for Redis..."
  for i in $(seq 1 20); do
    # Use REDIS_PASSWORD env var — never hardcode the password
    $DC exec -T redis redis-cli -a "${REDIS_PASSWORD}" ping 2>/dev/null | grep -q "PONG" && { ok "Redis ready"; break; }
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
  log "(First build: ~15-20 min — Maven downloads deps inside Docker)"
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
if [ "$CMD" = "infra" ]; then preflight && start_infra; exit 0; fi
if [ "$CMD" = "apps" ];  then preflight && start_apps;  exit 0; fi

# Default: start everything
preflight
start_infra
start_apps

echo ""
echo -e "${GREEN}══════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  VaultFlow is running!${NC}"
echo -e "${GREEN}══════════════════════════════════════════════════${NC}"
echo ""
echo "  Swagger (auth):   http://localhost:8081/swagger-ui.html"
echo "  Swagger (upload): http://localhost:8082/swagger-ui.html"
echo "  Grafana:          http://localhost:3001"
echo "  Jaeger:           http://localhost:16686"
echo "  Prometheus:       http://localhost:9091"
echo ""
echo "  PostgreSQL:  localhost:5433"
echo "  Redis:       localhost:6380"
echo "  Kafka:       localhost:9094"
echo ""
echo "  Quick test (register + get token):"
echo ""
SLUG="acme-$(date +%s)"
echo "    curl -s -X POST http://localhost:8081/api/v1/auth/register \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -d '{\"organizationName\":\"Acme\",\"organizationSlug\":\"${SLUG}\",\"fullName\":\"Alice\",\"email\":\"alice@acme.com\",\"password\":\"Password1!\"}' \\"
echo "      | python3 -m json.tool"
echo ""
echo "  Commands:"
echo "    ./start.sh status            re-check health"
echo "    ./start.sh logs auth-service  view a service's logs"
echo "    ./start.sh stop              stop everything"
echo "    ./start.sh clean             wipe + reset"
