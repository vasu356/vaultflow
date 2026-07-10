-- VaultFlow PostgreSQL initialization script
-- Run once when the container is first created

-- Ensure required extensions are available
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
CREATE EXTENSION IF NOT EXISTS "btree_gin";

-- Performance tuning (applied at session level; tune postgresql.conf for production)
ALTER DATABASE vaultflow SET work_mem = '64MB';
ALTER DATABASE vaultflow SET maintenance_work_mem = '256MB';
ALTER DATABASE vaultflow SET random_page_cost = 1.1;  -- SSD-optimized
ALTER DATABASE vaultflow SET effective_cache_size = '1GB';

-- Log slow queries (>500ms) for performance analysis
ALTER DATABASE vaultflow SET log_min_duration_statement = 500;

GRANT ALL PRIVILEGES ON DATABASE vaultflow TO vaultflow;
