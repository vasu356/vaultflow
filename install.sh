#!/usr/bin/env bash
# =============================================================================
# VaultFlow — Dependency Installer for Ubuntu/WSL
# Installs: docker compose plugin, java 21, maven 3.9
# =============================================================================

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log()  { echo -e "${BLUE}[install]${NC} $*"; }
ok()   { echo -e "${GREEN}[OK]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }

echo ""
echo -e "${BLUE}VaultFlow Dependency Installer${NC}"
echo "=============================="
echo ""

# ── Docker ────────────────────────────────────────────────────────────────────
if ! command -v docker &>/dev/null; then
    fail "Docker is not installed. Install Docker Desktop first: https://docs.docker.com/desktop/"
fi
ok "Docker found: $(docker --version)"

# ── Docker Compose Plugin ─────────────────────────────────────────────────────
if docker compose version &>/dev/null 2>&1; then
    ok "docker compose plugin already installed: $(docker compose version)"
elif command -v docker-compose &>/dev/null; then
    ok "docker-compose v1 found: $(docker-compose --version)"
    warn "Consider upgrading to docker compose v2 plugin for better compatibility"
else
    log "Installing docker compose plugin..."
    sudo apt-get update -qq
    sudo apt-get install -y docker-compose-plugin
    ok "docker compose plugin installed"
fi

# ── Java 21 ──────────────────────────────────────────────────────────────────
if command -v java &>/dev/null; then
    JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    if [ "$JAVA_VER" -ge 21 ] 2>/dev/null; then
        ok "Java $JAVA_VER already installed"
    else
        warn "Java $JAVA_VER found but Java 21 needed for local development"
        log "Installing Java 21..."
        sudo apt-get update -qq
        sudo apt-get install -y openjdk-21-jdk
        ok "Java 21 installed"
    fi
else
    log "Installing Java 21..."
    sudo apt-get update -qq
    sudo apt-get install -y openjdk-21-jdk
    ok "Java 21 installed"
fi

# ── Maven ─────────────────────────────────────────────────────────────────────
if command -v mvn &>/dev/null; then
    ok "Maven already installed: $(mvn --version | head -1)"
else
    log "Installing Maven 3.9..."
    sudo apt-get update -qq
    sudo apt-get install -y maven
    ok "Maven installed"
fi

# ── curl + python3 (for health checks) ───────────────────────────────────────
for tool in curl python3; do
    if command -v $tool &>/dev/null; then
        ok "$tool found"
    else
        log "Installing $tool..."
        sudo apt-get install -y $tool
    fi
done

echo ""
echo -e "${GREEN}All dependencies installed!${NC}"
echo ""
echo "Next steps:"
echo "  1. cd vaultflow"
echo "  2. chmod +x start.sh"
echo "  3. ./start.sh"
