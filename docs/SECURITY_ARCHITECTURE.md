# Security Architecture

This document describes VaultFlow's security design in depth. For the responsible disclosure policy, see [SECURITY.md](../SECURITY.md).

---

## Threat Model

### Assets

| Asset | Sensitivity | Protection Mechanism |
|---|---|---|
| File content (objects) | High — customer data | RBAC, signed URL restrictions, per-org isolation |
| User credentials (passwords) | Critical | BCrypt(cost=12) — never stored plaintext |
| JWT private key | Critical | Held exclusively by auth-service; mounted from Kubernetes Secret |
| Refresh tokens | High | Stored as SHA-256 hash — raw token never persisted |
| Signed URL secret | High | Environment variable / Kubernetes Secret; rotate quarterly |
| Audit logs | Medium | Append-only PostgreSQL; Admin-role access only |

### Threat Actors

| Actor | Capability | Primary Controls |
|---|---|---|
| Unauthenticated external | HTTP requests | Rate limiting, RBAC (no access without valid JWT) |
| Authenticated user (wrong org) | Valid JWT | Org-scoped isolation — all queries filtered by `org_id` |
| Compromised service (not auth-service) | Public key only | RS256 asymmetric JWT — cannot forge tokens |
| Database breach | Read access to DB | Passwords are BCrypt hashes; refresh tokens are SHA-256 hashes |
| Network eavesdropper | Packet capture | TLS 1.3 at NGINX; HSTS enforced |
| Malicious file upload | Crafted payloads | Content-type detection (Tika); virus scanning; no server-side execution |

---

## Authentication Architecture

### Why RS256 over HMAC-256

With 7 services all validating JWTs, HMAC-256 would require all 7 to share the same symmetric secret. A secret leak in any service (e.g., processing-service, which runs third-party processors) compromises the entire auth system.

RS256 asymmetric signing solves this:

```
auth-service:        private key (signs tokens)     ← secret, never leaves the pod
all other services:  public key only (verifies)      ← not secret; public by design
```

If processing-service is compromised, the attacker has the public key — which is already publicly available at `/.well-known/jwks.json`. They cannot issue new tokens.

### JWT Claims

```json
{
  "iss": "vaultflow-auth",
  "sub": "user-uuid",
  "orgId": "org-uuid",
  "email": "alice@acme.com",
  "role": "EDITOR",
  "jti": "token-uuid",      // JTI for blacklist on logout
  "iat": 1728998400,
  "exp": 1728999300         // 15 minutes from iat
}
```

The `jti` (JWT ID) is stored in Redis on logout, enabling immediate revocation of access tokens despite their stateless nature.

### Refresh Token Security

```
Raw refresh token → SHA-256 → stored in database
                 ↓
            NOT stored
```

A full database dump does not expose usable refresh tokens. An attacker would need the raw token string (which is only transmitted over TLS to the client).

**Token family theft detection:**

```
User A logs in → refresh_token_1 (family_id: F1)
User A refreshes → refresh_token_2 (family_id: F1), refresh_token_1 revoked

Attacker steals refresh_token_1 and tries to use it:
→ DB lookup finds refresh_token_1 is revoked
→ System detects potential theft: ALL tokens with family_id F1 are revoked
→ User A is logged out of all devices
→ Auth-service can optionally alert the user
```

---

## Authorization Design

### RBAC Role Hierarchy

```
OWNER
  └── ADMIN
        └── EDITOR
              └── VIEWER
```

Each role includes all permissions of roles below it. Roles are scoped per organization — a user can be `OWNER` in one organization and `VIEWER` in another.

### Enforcement Points

Authorization is enforced at the **service layer**, not the controller layer. Controllers extract the `VaultFlowUserPrincipal` from the security context and pass it to service methods. Services verify org membership and role:

```java
// In UploadService:
private Bucket validateBucketAccess(UUID bucketId, String orgId) {
    return bucketRepository.findByIdAndOrgId(bucketId, UUID.fromString(orgId))
        .orElseThrow(() -> new ResourceNotFoundException("Bucket", bucketId.toString()));
    // Note: returns 404, not 403, to avoid leaking that the bucket exists
}
```

Returning 404 instead of 403 when the resource belongs to another organization prevents cross-org enumeration. An attacker cannot determine whether bucket UUID X exists in another org.

### Org Isolation

Every database query that returns user data is filtered by `org_id`. There are no cross-organization queries in normal operation (cross-org dedup is intentionally disabled — see EDR-001).

---

## Content Security

### Content-Type Detection

Clients cannot be trusted to supply an accurate `Content-Type`. VaultFlow uses **Apache Tika** to detect content type by inspecting the first few kilobytes of the file (magic bytes):

```java
String resolvedContentType = contentTypeDetector.detect(
    new ByteArrayInputStream(fileBytes),
    clientSuppliedContentType,   // recorded but not trusted
    objectKey                    // used as fallback hint
);
```

This prevents MIME type confusion attacks (e.g., uploading a `text/html` file as `application/pdf` to serve in a browser context).

### Checksum Integrity

Clients may optionally supply `X-Checksum-SHA256`. If provided, the server verifies it:

```java
if (expectedChecksum != null && !expectedChecksum.isBlank()) {
    ChecksumUtil.verify(expectedChecksum.toLowerCase(), checksum);
}
```

This detects file corruption in transit. The server-computed SHA-256 is also the deduplication key and the storage path — so it is computed regardless of whether the client provides an expected checksum.

### Virus Scanning

Every uploaded file is virus scanned asynchronously:

```
Upload → Kafka → processing-service → VirusScanProcessor → update virus_scan_status
                                                           ↓
download-service checks virus_scan_status before serving → 403 if INFECTED
```

**Development implementation**: EICAR pattern detection (string matching on the standardized EICAR test signature).

**Production implementation**: Integrate ClamAV via TCP socket. The `VirusScanProcessor` interface is designed for this swap.

**Important**: Upload returns HTTP 200 before virus scanning completes. Files are served in `PENDING` state. If you require virus scanning before serving, add a `pending_until_clean` flag and have download-service wait for `CLEAN` status.

---

## Signed URLs

Signed URLs enable unauthenticated download of specific object versions for a limited time.

### Signature Scheme

```
token = base64url(version_id + ":" + expires_at + ":" + max_downloads + ":" + allowed_ip)
mac   = HMAC-SHA256(SIGNED_URL_SECRET, token)
url   = /api/v1/download/signed?t={token}&s={mac}
```

Validation on download:

1. Recompute HMAC-SHA256 using server-side secret
2. Constant-time comparison to `s` parameter (prevents timing attacks)
3. Check `expires_at` against current time
4. Check `used_count < max_downloads` (if limit set)
5. Check client IP against `allowed_ip` CIDR (if restriction set)

### Security Considerations

- **Secret rotation**: `SIGNED_URL_SECRET` rotation invalidates all existing signed URLs. Plan rotation windows accordingly or implement multi-key signing.
- **URL length**: Signed URLs are not guessable without the server secret. However, once shared, anyone with the URL can use it until expiry.
- **HTTPS only**: Signed URLs contain time-limited authentication material. Never serve them over HTTP in production.

---

## Transport Security

### NGINX Security Headers

All responses include:

```
Strict-Transport-Security: max-age=63072000; includeSubDomains
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
X-XSS-Protection: 1; mode=block
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: camera=(), microphone=(), geolocation=()
```

`server_tokens off` removes the NGINX version from error pages and `Server` headers.

### Rate Limiting

NGINX `limit_req_zone` provides connection-level rate limiting:

| Zone | Rate | Burst | Applies To |
|---|---|---|---|
| `auth_limit` | 10 req/s | 20 | `/api/v1/auth/*` |
| `upload_limit` | 100 req/s | 200 | Upload endpoints |
| `download_limit` | 200 req/s | 500 | Download endpoints |
| `api_limit` | 50 req/s | 100 | All other API endpoints |

Exceeding burst returns HTTP 429. This protects against brute-force credential stuffing on the auth endpoints.

### Account Lockout

5 consecutive failed login attempts lock the account until `locked_until` (configurable backoff). This prevents online brute force even at the application level, independent of NGINX rate limiting.

---

## Secrets Management

### Development

```yaml
# docker-compose.yml — for local development only
# All secrets come from .env (see .env.example); never hardcode them
environment:
  DB_PASSWORD: ${DB_PASSWORD}
  REDIS_PASSWORD: ${REDIS_PASSWORD}
```

**Never use development defaults in production.**

### Production (Kubernetes)

```bash
# Create secrets
kubectl create secret generic vaultflow-secrets \
  --from-literal=DB_PASSWORD=$(openssl rand -hex 32) \
  --from-literal=REDIS_PASSWORD=$(openssl rand -hex 32) \
  --from-literal=SIGNED_URL_SECRET=$(openssl rand -hex 32) \
  -n vaultflow

# Mount in pod spec
envFrom:
  - secretRef:
      name: vaultflow-secrets
```

Restrict secret access via RBAC:

```yaml
# Allow only the vaultflow service account to read secrets
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  namespace: vaultflow
  name: secret-reader
rules:
  - apiGroups: [""]
    resources: ["secrets"]
    resourceNames: ["vaultflow-secrets", "vaultflow-jwt-keys"]
    verbs: ["get"]
```

### HashiCorp Vault (Recommended for Production)

Use Vault Agent Sidecar Injector to inject secrets as environment variables or files:

```yaml
annotations:
  vault.hashicorp.com/agent-inject: "true"
  vault.hashicorp.com/role: "vaultflow"
  vault.hashicorp.com/agent-inject-secret-db: "secret/vaultflow/db"
  vault.hashicorp.com/agent-inject-template-db: |
    {{- with secret "secret/vaultflow/db" -}}
    export DB_PASSWORD="{{ .Data.data.password }}"
    {{- end }}
```

---

## Audit Trail

### What Is Audited

Every write operation emits an `AuditEvent` to Kafka:

| Action | When |
|---|---|
| `ORG_REGISTERED` | New organization created |
| `USER_CREATED` | New user added to org |
| `USER_LOGIN` | Successful login |
| `USER_LOGIN_FAILED` | Failed login attempt |
| `BUCKET_CREATED` | New bucket created |
| `OBJECT_UPLOADED` | File uploaded (includes version ID) |
| `OBJECT_DELETED` | Object soft-deleted |
| `OBJECT_RESTORED` | Soft-deleted object restored |
| `QUOTA_UPDATED` | Organization quota changed |
| `TOKEN_REVOKED` | Refresh token explicitly revoked |

### Audit Log Protection

- Audit logs are **append-only** — there is no update or delete API
- Admin role is required to read audit logs via the API
- PostgreSQL partitioning enables archival without deletion (`DETACH PARTITION`)
- The Kafka `audit.events` topic has a 30-day retention — even if the database write fails, events can be replayed

---

## Penetration Testing

VaultFlow welcomes security research. Before testing:

1. Email security@vaultflow.io to notify us
2. Limit testing to your own tenant (do not test against other users' data)
3. Do not perform denial-of-service testing against shared infrastructure
4. Report findings via email before public disclosure

Common test scenarios we recommend:

- JWT signature algorithm confusion (RS256 vs HS256 downgrade)
- Token replay after logout
- Org isolation bypass (accessing another org's objects)
- Signed URL token forgery
- Content-type bypass (serving malicious content with wrong MIME type)
- Account enumeration via timing differences
- Path traversal on object keys
