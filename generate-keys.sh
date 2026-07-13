#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# VaultFlow — RSA key pair generator (run ONCE before first `docker-compose up`)
#
# Generates:
#   keys/private.pem  — 2048-bit RSA private key (auth-service only)
#   keys/public.pem   — corresponding public key (all services)
#
# These files are gitignored. Never commit them.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

KEYS_DIR="$(cd "$(dirname "$0")" && pwd)/keys"

if [ -f "$KEYS_DIR/private.pem" ] && [ -f "$KEYS_DIR/public.pem" ]; then
  echo "Keys already exist at $KEYS_DIR — skipping generation."
  echo "Delete them manually and re-run if you need to rotate."
  exit 0
fi

mkdir -p "$KEYS_DIR"
chmod 700 "$KEYS_DIR"

echo "Generating 2048-bit RSA private key..."
openssl genrsa -out "$KEYS_DIR/private.pem" 2048
chmod 600 "$KEYS_DIR/private.pem"

echo "Deriving public key..."
openssl rsa -in "$KEYS_DIR/private.pem" -pubout -out "$KEYS_DIR/public.pem"
chmod 644 "$KEYS_DIR/public.pem"

echo ""
echo "Done. Keys written to:"
echo "  $KEYS_DIR/private.pem"
echo "  $KEYS_DIR/public.pem"
echo ""
echo "Next: docker-compose up -d"
