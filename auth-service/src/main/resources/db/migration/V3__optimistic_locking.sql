-- VaultFlow Schema Migration V3: Optimistic Locking Columns
-- Adds @Version columns required by JPA optimistic locking on User and StoredObject entities.
--
-- Why optimistic locking?
-- User: concurrent role updates or profile changes from multiple admin sessions
-- StoredObject: concurrent uploads to the same object key (same-key PUT race)
--
-- The @Version column is managed entirely by Hibernate — it increments on every
-- UPDATE and throws OptimisticLockException if two transactions read the same version
-- and both try to write. This is cheaper than a SELECT FOR UPDATE (no lock held).
--
-- Default value 0: existing rows get version=0, first UPDATE bumps to 1.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE objects
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN users.version IS 'JPA optimistic lock version. Incremented on every UPDATE by Hibernate.';
COMMENT ON COLUMN objects.version IS 'JPA optimistic lock version. Prevents concurrent PUT race on same object key.';
