-- Persisted per-tenant "startup schema migration failed" flag.
--
-- Until now a failed tenant-schema migration was remembered only in the memory of the JVM that ran
-- it, so the jobs / worker / migrate-only shapes and every other API pod kept serving a tenant
-- whose schema was stale. TenantMigrationFailures now records the failure on the tenant row;
-- TenantStatusGuardImpl reads it (through its TTL cache) on every shape, and a later successful
-- migration clears it. Nullable and additive: pods still on the previous version ignore the columns.

ALTER TABLE control_plane.tenant
    ADD COLUMN IF NOT EXISTS migration_failed_at timestamptz,
    ADD COLUMN IF NOT EXISTS migration_error varchar(500);

CREATE INDEX IF NOT EXISTS idx_tenant_migration_failed
    ON control_plane.tenant (migration_failed_at)
    WHERE migration_failed_at IS NOT NULL;
