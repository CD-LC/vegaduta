-- Platform-admin "delete tenant completely" control - the durable,
-- control-plane-resident record that a deletion was ATTEMPTED, before any
-- destructive step runs. Deliberately not the normal per-tenant
-- audit_log_entry mechanism (db/migration/tenant/) - that table lives
-- INSIDE the schema this feature drops, so a row written there moments
-- before DROP SCHEMA would be destroyed along with everything else it was
-- trying to record (same reasoning V96__tenant_data_residency_and_erasure.sql
-- already gives for tenant.erased_at/erased_by). This table is that same
-- "survives the drop" posture, but as a real row per attempt rather than two
-- columns on tenant - it also has to represent an INITIATED-but-not-yet-
-- COMPLETED state (the audit-before-delete gate) and a FAILED outcome with
-- detail, neither of which two columns can express.
--
-- tenant_name is denormalized (copied at initiation time, not a live join)
-- so this row stays legible even if a future change ever hard-deletes the
-- tenant row itself instead of soft-marking it ERASED.
CREATE TABLE control_plane.platform_tenant_deletion_log (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    tenant_name VARCHAR(255) NOT NULL,
    actor_id VARCHAR(255),
    status VARCHAR(16) NOT NULL,
    initiated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    failure_detail TEXT,
    steps_completed TEXT
);

CREATE INDEX idx_platform_tenant_deletion_log_tenant_id
    ON control_plane.platform_tenant_deletion_log (tenant_id);
