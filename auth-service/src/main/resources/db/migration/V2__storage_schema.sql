-- VaultFlow Schema Migration V2: Storage Domain
-- Buckets, Objects, Object Versions, Upload Sessions, Upload Parts
--
-- Design decisions:
-- 1. Objects vs ObjectVersions: objects table holds the logical file (stable ID referenced
--    by other systems). object_versions holds the physical data per version. This separation
--    means external references to an object ID remain valid across PUTs.
-- 2. storage_key uses content-addressing (SHA-256 of file content). Two identical files
--    share one storage_key, enabling deduplication at rest.
-- 3. upload_sessions: multipart upload state is ephemeral. Redis holds hot state;
--    PostgreSQL is the durable fallback for crash recovery.
-- 4. Partitioned audit_logs by month: avoid hot spots, enable cheap archival by partition drop.

-- ============================================================
-- BUCKETS
-- ============================================================
CREATE TABLE buckets (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  UUID        NOT NULL REFERENCES organizations (id),
    name                    TEXT        NOT NULL,
    region                  TEXT        NOT NULL DEFAULT 'ap-south-1',
    versioning_enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    public_access_enabled   BOOLEAN     NOT NULL DEFAULT FALSE,
    default_storage_class   TEXT        NOT NULL DEFAULT 'STANDARD'
                                CHECK (default_storage_class IN ('STANDARD', 'INFREQUENT', 'ARCHIVE')),
    lifecycle_rules         JSONB       NOT NULL DEFAULT '[]',
    retention_days          INT,        -- NULL = no retention policy
    cors_config             JSONB       NOT NULL DEFAULT '[]',
    tags                    JSONB       NOT NULL DEFAULT '{}',
    status                  TEXT        NOT NULL DEFAULT 'ACTIVE'
                                CHECK (status IN ('ACTIVE', 'DELETED')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT buckets_name_org_unique UNIQUE (org_id, name),
    CONSTRAINT buckets_name_format CHECK (name ~ '^[a-z0-9][a-z0-9\-]{1,61}[a-z0-9]$')
);

CREATE INDEX idx_buckets_org_id ON buckets (org_id);
CREATE INDEX idx_buckets_org_name ON buckets (org_id, name) WHERE status = 'ACTIVE';

COMMENT ON TABLE buckets IS 'Logical namespace for objects. Analogous to S3 bucket or GCS bucket.';
COMMENT ON COLUMN buckets.lifecycle_rules IS 'JSON array of lifecycle rules: [{action: EXPIRE, condition: {ageDays: 90}}]';
COMMENT ON COLUMN buckets.retention_days IS 'Objects cannot be permanently deleted within retention_days of creation. Supports compliance use cases.';

-- ============================================================
-- OBJECTS (logical entity)
-- ============================================================
CREATE TABLE objects (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    bucket_id           UUID        NOT NULL REFERENCES buckets (id),
    object_key          TEXT        NOT NULL,   -- e.g. "documents/2024/report.pdf"
    current_version_id  UUID,                   -- FK set after first version created (circular reference resolved by deferred constraint)
    content_type        TEXT,
    is_deleted          BOOLEAN     NOT NULL DEFAULT FALSE,
    deleted_at          TIMESTAMPTZ,
    delete_marker_id    UUID,                   -- version ID that represents the delete marker
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT objects_key_bucket_unique UNIQUE (bucket_id, object_key)
);

-- Primary access pattern: bucket + key lookup (most frequent query)
CREATE INDEX idx_objects_bucket_key ON objects (bucket_id, object_key)
    WHERE is_deleted = FALSE;

-- For listing objects in a bucket with prefix (simulates S3 ListObjectsV2)
CREATE INDEX idx_objects_bucket_key_prefix ON objects (bucket_id, object_key text_pattern_ops)
    WHERE is_deleted = FALSE;

COMMENT ON TABLE objects IS 'Logical file entity. Stable across multiple versions. External systems reference this ID.';
COMMENT ON COLUMN objects.object_key IS 'Forward-slash delimited path within bucket. Max 1024 chars. Unicode supported.';
COMMENT ON COLUMN objects.current_version_id IS 'Points to the active (latest non-deleted) version.';

-- ============================================================
-- OBJECT VERSIONS (physical data per version)
-- ============================================================
CREATE TABLE object_versions (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    object_id           UUID        NOT NULL REFERENCES objects (id),
    storage_key         TEXT        NOT NULL,  -- content-addressed: SHA-256 based path
    size_bytes          BIGINT      NOT NULL,
    checksum_sha256     TEXT        NOT NULL,
    etag                TEXT        NOT NULL,  -- MD5 of content (S3 compatibility) or multipart ETag
    version_number      INT         NOT NULL,  -- monotonically increasing per object
    storage_class       TEXT        NOT NULL DEFAULT 'STANDARD',
    content_type        TEXT        NOT NULL,
    content_encoding    TEXT,
    content_disposition TEXT,
    metadata            JSONB       NOT NULL DEFAULT '{}',   -- user-defined metadata
    tags                JSONB       NOT NULL DEFAULT '{}',   -- user-defined tags
    is_latest           BOOLEAN     NOT NULL DEFAULT TRUE,
    is_delete_marker    BOOLEAN     NOT NULL DEFAULT FALSE,
    processing_status   TEXT        NOT NULL DEFAULT 'PENDING'
                            CHECK (processing_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    thumbnail_key       TEXT,       -- storage key of generated thumbnail
    preview_key         TEXT,       -- storage key of PDF preview
    virus_scan_status   TEXT        NOT NULL DEFAULT 'PENDING'
                            CHECK (virus_scan_status IN ('PENDING', 'CLEAN', 'INFECTED', 'ERROR', 'SKIPPED')),
    virus_scan_at       TIMESTAMPTZ,
    ref_count           INT         NOT NULL DEFAULT 1,  -- dedup reference count
    uploaded_by         UUID        REFERENCES users (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT ov_version_number_unique UNIQUE (object_id, version_number),
    CONSTRAINT ov_size_positive CHECK (size_bytes >= 0),
    CONSTRAINT ov_ref_count_positive CHECK (ref_count >= 0)
);

-- Deduplication lookup: given a SHA-256, find existing stored version
CREATE INDEX idx_ov_storage_key ON object_versions (storage_key);
CREATE INDEX idx_ov_object_id ON object_versions (object_id);
CREATE INDEX idx_ov_object_latest ON object_versions (object_id) WHERE is_latest = TRUE;
CREATE INDEX idx_ov_processing_status ON object_versions (processing_status)
    WHERE processing_status IN ('PENDING', 'PROCESSING');
CREATE INDEX idx_ov_virus_scan ON object_versions (virus_scan_status)
    WHERE virus_scan_status = 'PENDING';

COMMENT ON TABLE object_versions IS 'Physical version of an object. Multiple versions per object when versioning enabled.';
COMMENT ON COLUMN object_versions.storage_key IS 'Content-addressed path: sha256[0:3]/sha256[3:6]/sha256. Shared across versions with identical content.';
COMMENT ON COLUMN object_versions.ref_count IS 'Number of object_versions pointing to this storage_key. Physical file deleted only when ref_count reaches 0.';
COMMENT ON COLUMN object_versions.etag IS 'S3-compatible ETag. For single-part: MD5 hex. For multipart: MD5(concat(part_MD5s))-N where N is part count.';

-- Add deferred FK from objects.current_version_id to object_versions
ALTER TABLE objects
    ADD CONSTRAINT fk_objects_current_version
    FOREIGN KEY (current_version_id) REFERENCES object_versions (id)
    DEFERRABLE INITIALLY DEFERRED;

-- ============================================================
-- UPLOAD SESSIONS (multipart upload coordination)
-- ============================================================
CREATE TABLE upload_sessions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    bucket_id       UUID        NOT NULL REFERENCES buckets (id),
    object_key      TEXT        NOT NULL,
    org_id          UUID        NOT NULL REFERENCES organizations (id),
    initiated_by    UUID        NOT NULL REFERENCES users (id),
    content_type    TEXT        NOT NULL,
    expected_size   BIGINT,     -- client-provided, used for pre-validation
    total_parts     INT,        -- known after initiation, NULL for streaming uploads
    metadata        JSONB       NOT NULL DEFAULT '{}',
    tags            JSONB       NOT NULL DEFAULT '{}',
    status          TEXT        NOT NULL DEFAULT 'INITIATED'
                        CHECK (status IN ('INITIATED', 'UPLOADING', 'COMPLETING', 'COMPLETED', 'ABORTED', 'EXPIRED')),
    object_id       UUID        REFERENCES objects (id),  -- set on completion
    version_id      UUID        REFERENCES object_versions (id),  -- set on completion
    expires_at      TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '24 hours',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_upload_sessions_org ON upload_sessions (org_id);
CREATE INDEX idx_upload_sessions_status ON upload_sessions (status, expires_at)
    WHERE status IN ('INITIATED', 'UPLOADING');
CREATE INDEX idx_upload_sessions_initiated_by ON upload_sessions (initiated_by);

COMMENT ON TABLE upload_sessions IS 'Tracks multipart/chunked upload progress. Enables resumable uploads. Expired sessions cleaned by scheduler.';

-- ============================================================
-- UPLOAD PARTS
-- ============================================================
CREATE TABLE upload_parts (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID        NOT NULL REFERENCES upload_sessions (id) ON DELETE CASCADE,
    part_number     INT         NOT NULL,
    storage_key     TEXT        NOT NULL,  -- temp key: sessions/{session_id}/part_{number}
    size_bytes      BIGINT      NOT NULL,
    checksum_md5    TEXT        NOT NULL,
    checksum_sha256 TEXT        NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT upload_parts_session_part_unique UNIQUE (session_id, part_number),
    CONSTRAINT upload_parts_part_number_positive CHECK (part_number >= 1),
    CONSTRAINT upload_parts_size_positive CHECK (size_bytes > 0)
);

CREATE INDEX idx_upload_parts_session ON upload_parts (session_id);

-- ============================================================
-- SIGNED URLS
-- ============================================================
CREATE TABLE signed_urls (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    object_version_id   UUID        NOT NULL REFERENCES object_versions (id),
    token               TEXT        NOT NULL,  -- HMAC-SHA256 token (opaque to client)
    expires_at          TIMESTAMPTZ NOT NULL,
    max_downloads       INT,        -- NULL = unlimited within TTL
    download_count      INT         NOT NULL DEFAULT 0,
    allowed_ip          INET,       -- NULL = any IP
    created_by          UUID        REFERENCES users (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT signed_urls_token_unique UNIQUE (token)
);

CREATE INDEX idx_signed_urls_token ON signed_urls (token);
CREATE INDEX idx_signed_urls_version ON signed_urls (object_version_id);

COMMENT ON TABLE signed_urls IS 'Time-limited download tokens. Validated by download-service without requiring authentication.';

-- ============================================================
-- AUDIT LOGS (partitioned by month)
-- ============================================================
CREATE TABLE audit_logs (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    org_id          UUID        NOT NULL,  -- Intentionally no FK: audit must survive org deletion
    user_id         UUID,
    action          TEXT        NOT NULL,
    resource_type   TEXT,
    resource_id     UUID,
    ip_address      INET,
    user_agent      TEXT,
    correlation_id  TEXT,
    outcome         TEXT        NOT NULL DEFAULT 'SUCCESS'
                        CHECK (outcome IN ('SUCCESS', 'FAILURE', 'PARTIAL')),
    metadata        JSONB       NOT NULL DEFAULT '{}',
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (id, occurred_at)  -- Partition key must be in PK for declarative partitioning
) PARTITION BY RANGE (occurred_at);

-- Create partitions for 12 months ahead (production: automate via pg_partman)
DO $$
DECLARE
    start_date DATE;
    end_date DATE;
    partition_name TEXT;
BEGIN
    FOR i IN 0..11 LOOP
        start_date := DATE_TRUNC('month', NOW()) + (i || ' months')::INTERVAL;
        end_date := start_date + '1 month'::INTERVAL;
        partition_name := 'audit_logs_' || TO_CHAR(start_date, 'YYYY_MM');

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_logs
             FOR VALUES FROM (%L) TO (%L)',
            partition_name, start_date, end_date
        );
    END LOOP;
END $$;

CREATE INDEX idx_audit_logs_org_occurred ON audit_logs (org_id, occurred_at DESC);
CREATE INDEX idx_audit_logs_resource ON audit_logs (resource_type, resource_id);
CREATE INDEX idx_audit_logs_user ON audit_logs (user_id, occurred_at DESC);

COMMENT ON TABLE audit_logs IS 'Immutable audit trail. Partitioned by month for efficient archival. No FK to org (audit outlives org).';

-- ============================================================
-- UPDATED_AT TRIGGERS
-- ============================================================
CREATE TRIGGER trg_buckets_updated_at
    BEFORE UPDATE ON buckets
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_objects_updated_at
    BEFORE UPDATE ON objects
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_upload_sessions_updated_at
    BEFORE UPDATE ON upload_sessions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
