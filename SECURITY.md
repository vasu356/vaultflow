# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| `main` branch | ✅ Security fixes applied |
| `develop` branch | ✅ Security fixes applied |
| Older tagged releases | ❌ Upgrade to latest |

---

## Reporting a Vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

To report a vulnerability, please email **security@vaultflow.io** with:

1. A clear description of the vulnerability
2. Steps to reproduce (proof of concept if applicable)
3. Affected component(s) and version(s)
4. Potential impact assessment
5. Any suggested mitigations

You will receive an acknowledgement within **48 hours** and a full response within **7 business days**.

### What to Expect

| Timeline | Action |
|---|---|
| 0–48 hours | Acknowledgement of receipt |
| 2–7 days | Initial triage and severity assessment |
| 7–30 days | Fix development and testing |
| 30–90 days | Coordinated disclosure and release |

We follow a **coordinated disclosure** model. We will work with you to understand the issue and coordinate a public disclosure after a fix is available. We will credit reporters in release notes unless they prefer to remain anonymous.

---

## Security Architecture

### Authentication Flow

```
Client                          Auth Service                         Other Services
  │                                  │                                     │
  ├─ POST /auth/login ──────────────►│                                     │
  │                                  │  1. BCrypt verify password          │
  │                                  │  2. Sign JWT with RSA private key   │
  │                                  │     (HS not used — private key      │
  │                                  │      never leaves auth-service)     │
  │◄─── access_token (15 min) ──────┤                                     │
  │◄─── refresh_token (7 days) ─────┤                                     │
  │                                  │                                     │
  ├─ PUT /objects/{key} ──── Bearer access_token ──────────────────────────►
  │                                  │                                     │
  │                                  │           Validate RS256 JWT        │
  │                                  │           with public key only      │
  │                                  │           (no network call needed)  │
  │◄──── 200 OK ──────────────────────────────────────────────────────────┤
```

### JWT Security Properties

- **Algorithm**: RS256 (RSA-SHA256 asymmetric). The private key is held exclusively by auth-service. All other services hold only the public key, distributed via `/.well-known/jwks.json`.
- **Access token TTL**: 15 minutes. Short expiry limits the window of stolen token misuse.
- **Refresh token storage**: SHA-256 hash stored in PostgreSQL. The raw token is never persisted — a database breach does not expose usable tokens.
- **Refresh token rotation**: Each refresh issues a new token and revokes the old one. If a previously-revoked token is presented, the entire token family is invalidated (theft detection).
- **Token revocation**: Access token JTIs are added to a Redis blacklist on logout (TTL matches token remaining lifetime). Refresh tokens are marked revoked in PostgreSQL.

### Defense in Depth

| Layer | Controls |
|---|---|
| **Network** | NGINX rate limiting (`limit_req_zone`); HSTS; security headers (`X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`) |
| **Authentication** | RS256 JWT; short-lived access tokens; refresh token rotation with theft detection |
| **Authorization** | Role-based (`OWNER > ADMIN > EDITOR > VIEWER`); all checks enforced in service layer, not just controller |
| **Input validation** | Apache Tika magic-byte content detection (client `Content-Type` untrusted); SHA-256 checksum verification; part number and size bounds checking |
| **Virus scanning** | EICAR pattern detection (development); ClamAV TCP integration (production) |
| **Signed URLs** | HMAC-SHA256; TTL expiry; optional IP CIDR restriction; download count limit |
| **Account lockout** | 5 consecutive failed logins triggers account lock with configurable backoff |
| **Audit trail** | All write operations emit `AuditEvent` to Kafka; audit log is append-only and partitioned for retention |
| **Secrets** | Environment variables (dev); Kubernetes Secrets (staging/prod); Vault Agent injection (production hardened) |

---

## Security Best Practices for Operators

### Secrets Management

**Never commit secrets to version control.**

| Secret | Where to Store |
|---|---|
| `DB_PASSWORD` | Kubernetes Secret / Vault |
| `REDIS_PASSWORD` | Kubernetes Secret / Vault |
| `SIGNED_URL_SECRET` | Kubernetes Secret / Vault (rotate quarterly) |
| RSA private key (`private.pem`) | Kubernetes Secret with restricted RBAC; mounted read-only |

```yaml
# Example Kubernetes Secret (values are base64-encoded)
apiVersion: v1
kind: Secret
metadata:
  name: vaultflow-secrets
  namespace: vaultflow
type: Opaque
data:
  DB_PASSWORD: <base64-encoded>
  REDIS_PASSWORD: <base64-encoded>
  SIGNED_URL_SECRET: <base64-encoded>
```

See `infrastructure/kubernetes/common/secrets-template.yaml` for the complete template.

### RSA Key Management

```bash
# Generate production key pair (4096-bit for production)
openssl genrsa -out private.pem 4096
openssl rsa -in private.pem -pubout -out public.pem

# Store private key as Kubernetes Secret
kubectl create secret generic vaultflow-jwt-keys \
  --from-file=private.pem=./private.pem \
  --from-file=public.pem=./public.pem \
  -n vaultflow

# Verify the secret
kubectl get secret vaultflow-jwt-keys -n vaultflow -o jsonpath='{.data.public\.pem}' | base64 -d
```

**Key rotation procedure:**
1. Generate new key pair
2. Add new public key to JWKS endpoint (dual-key window)
3. Deploy auth-service with new private key
4. Wait for all existing access tokens to expire (15 minutes)
5. Remove old public key from JWKS endpoint
6. Rotate the Kubernetes Secret

### Network Security

In production, restrict admin-service to internal network only:

```nginx
location /api/v1/admin/ {
    allow 10.0.0.0/8;
    allow 172.16.0.0/12;
    deny all;
    proxy_pass http://admin_backend;
}
```

Enable TLS in NGINX:

```nginx
server {
    listen 443 ssl http2;
    ssl_certificate /etc/nginx/ssl/fullchain.pem;
    ssl_certificate_key /etc/nginx/ssl/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384;
    ssl_prefer_server_ciphers off;
}
```

### Environment Variables

```bash
# Required in production (no defaults)
SIGNED_URL_SECRET        # Must be >= 256-bit entropy, rotated quarterly
DB_PASSWORD              # Must not be the development default "vaultflow"
REDIS_PASSWORD           # Required; do not run Redis without authentication

# Verify no development defaults leak to production
grep -r "vaultflow" infrastructure/kubernetes/ # Should return nothing
```

### Docker Security

All service Dockerfiles:
- Use multi-stage builds to minimize image surface area
- Run as a non-root user (`USER app`)
- Use minimal JRE base images

```bash
# Scan images before pushing
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  aquasec/trivy image ghcr.io/your-org/vaultflow/auth-service:latest
```

---

## Known Limitations

1. **Virus scanning in development** uses EICAR pattern detection, not a production antivirus engine. Before deploying to production, integrate ClamAV via TCP socket in `VirusScanProcessor`.

2. **Signed URL secrets** are currently symmetric (single HMAC secret). A future improvement is asymmetric signing for signed URLs to support secret rotation without invalidating existing URLs.

3. **Cross-organization deduplication** is intentionally disabled (dedup is scoped per organization) to prevent timing side-channel attacks that could reveal the existence of content in other organizations.

4. **JWT key rotation** requires a manual dual-key window procedure. Automation via a key management service is on the roadmap.

---

## Security Scanning in CI/CD

The CI/CD pipeline runs two security checks on every push:

1. **Trivy filesystem scan** — scans source code and dependencies for known CVEs (HIGH and CRITICAL severity fail the build)
2. **OWASP Dependency Check** — CVSS score ≥ 7 fails the build
3. **Trivy image scan** — scans each built Docker image before pushing

Security scan results are uploaded to the GitHub Security tab as SARIF for tracking and triage.
