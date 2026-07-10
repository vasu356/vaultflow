-- VaultFlow Schema Migration V1: Core Domain
-- Organizations, Users, RBAC, and Refresh Token store
--
-- Design decisions:
-- 1. UUID primary keys: globally unique, safe for distributed ID generation without coordination
-- 2. TIMESTAMPTZ (not TIMESTAMP): all times stored in UTC, avoids DST ambiguity
-- 3. password_hash: bcrypt hash stored, never plaintext
-- 4. role as TEXT not ENUM: easier to add roles without ALTER TYPE + table rewrite
-- 5. Soft-delete pattern on users: audit trail preserved, GDPR handled via separate anonymization job

-- Enable UUID generation
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm"; -- For future trigram search on object keys

-- ============================================================
-- ORGANIZATIONS
-- ============================================================
CREATE TABLE organizations (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT        NOT NULL,
    slug                TEXT        NOT NULL,   -- URL-safe unique identifier
    quota_bytes         BIGINT      NOT NULL DEFAULT 107374182400, -- 100 GB default
    used_bytes          BIGINT      NOT NULL DEFAULT 0,
    status              TEXT        NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    settings            JSONB       NOT NULL DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT orgs_slug_unique UNIQUE (slug),
    CONSTRAINT orgs_quota_positive CHECK (quota_bytes > 0),
    CONSTRAINT orgs_used_non_negative CHECK (used_bytes >= 0)
);

CREATE INDEX idx_orgs_slug ON organizations (slug);
CREATE INDEX idx_orgs_status ON organizations (status) WHERE status = 'ACTIVE';

COMMENT ON TABLE organizations IS 'Top-level tenant boundary. All resources belong to an organization.';
COMMENT ON COLUMN organizations.slug IS 'URL-safe lowercase identifier, e.g. acme-corp. Used in API paths.';
COMMENT ON COLUMN organizations.quota_bytes IS 'Maximum total storage in bytes. Default 100 GB.';
COMMENT ON COLUMN organizations.used_bytes IS 'Current storage consumption in bytes. Updated transactionally on every upload/delete.';

-- ============================================================
-- USERS
-- ============================================================
CREATE TABLE users (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID        NOT NULL REFERENCES organizations (id),
    email               TEXT        NOT NULL,
    password_hash       TEXT        NOT NULL,  -- BCrypt(cost=12)
    full_name           TEXT        NOT NULL,
    role                TEXT        NOT NULL DEFAULT 'VIEWER'
                            CHECK (role IN ('OWNER', 'ADMIN', 'EDITOR', 'VIEWER')),
    status              TEXT        NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE', 'SUSPENDED', 'PENDING_VERIFICATION', 'DELETED')),
    email_verified      BOOLEAN     NOT NULL DEFAULT FALSE,
    last_login_at       TIMESTAMPTZ,
    last_login_ip       INET,
    failed_login_count  INT         NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Email unique per org (same email can exist in different orgs)
    CONSTRAINT users_email_org_unique UNIQUE (org_id, email)
);

CREATE INDEX idx_users_email ON users (email);
CREATE INDEX idx_users_org_id ON users (org_id);
CREATE INDEX idx_users_org_role ON users (org_id, role);
CREATE INDEX idx_users_status ON users (status) WHERE status = 'ACTIVE';

COMMENT ON TABLE users IS 'Platform users. Email is unique within an organization, not globally.';
COMMENT ON COLUMN users.role IS 'RBAC role: OWNER (full control) > ADMIN > EDITOR > VIEWER';
COMMENT ON COLUMN users.failed_login_count IS 'Incremented on failed login. Account locked after 5 consecutive failures.';
COMMENT ON COLUMN users.locked_until IS 'If set and in future, login is rejected regardless of password.';

-- ============================================================
-- REFRESH TOKENS
-- ============================================================
-- Why store refresh tokens in DB?
-- 1. Enables explicit logout (revoke specific refresh token)
-- 2. Enables "logout all devices" (revoke all tokens for a user)
-- 3. Detects token theft via rotation (if a revoked token is used, family is invalidated)
-- Access tokens (15min TTL) are not stored — they expire naturally.
-- Redis also caches revoked JTIs for fast rejection without DB hit.

CREATE TABLE refresh_tokens (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash      TEXT        NOT NULL,  -- SHA-256 of actual token (don't store plaintext)
    family_id       UUID        NOT NULL,  -- Token rotation family for theft detection
    device_info     TEXT,
    ip_address      INET,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked         BOOLEAN     NOT NULL DEFAULT FALSE,
    revoked_at      TIMESTAMPTZ,
    revoke_reason   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_active ON refresh_tokens (user_id, expires_at)
    WHERE revoked = FALSE;

COMMENT ON TABLE refresh_tokens IS 'Stored refresh tokens for revocation support. Access tokens are stateless.';
COMMENT ON COLUMN refresh_tokens.family_id IS 'All tokens in a refresh rotation chain share a family_id. Reuse of a revoked token invalidates the entire family (theft detection).';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 of the raw token. Never store the actual token — hash prevents token exposure via DB breach.';

-- ============================================================
-- UPDATED_AT TRIGGER
-- ============================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_organizations_updated_at
    BEFORE UPDATE ON organizations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
