-- Master Roadmap 2026 item #13 (A/B testing & feature flags, §B). See
-- FeatureFlag/ExperimentAssignment/FeatureFlagService's javadoc (core/tenancy)
-- for the full design. Both tables are control-plane-scoped like
-- platform_kill_switch/platform_setting - a flag definition is a
-- platform-wide concept, evaluated per-tenant at read time, not stored
-- per-tenant.
CREATE TABLE control_plane.feature_flag (
    id                  UUID PRIMARY KEY,
    flag_key            VARCHAR(120) NOT NULL UNIQUE,
    description         VARCHAR(500),
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    rollout_percentage  INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    updated_by          VARCHAR(255)
);

-- Observability/audit trail of which tenant landed in which bucket, written
-- best-effort by FeatureFlagService.evaluate - NOT the source of truth for
-- the bucketing decision itself (that's a deterministic hash, recomputed
-- every call - see FeatureFlagService's javadoc). One row per (flag,
-- tenant) pair, first-assignment-wins.
CREATE TABLE control_plane.experiment_assignment (
    id           UUID PRIMARY KEY,
    flag_key     VARCHAR(120) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL,
    variant      VARCHAR(32) NOT NULL,
    assigned_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_experiment_assignment_flag_tenant UNIQUE (flag_key, tenant_id)
);

CREATE INDEX idx_experiment_assignment_flag_key ON control_plane.experiment_assignment (flag_key);
