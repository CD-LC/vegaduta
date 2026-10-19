-- Issue 8 fix: idempotency/audit log for the daily token-quota-counter
-- reset job (DailyQuotaResetScheduler). One row per (job_name, reset_date),
-- enforced by the unique constraint below - this is the DB-level guard that
-- makes the scheduled reset both idempotent (a duplicate scheduler tick
-- can't insert a second row for the same day) and safe across
-- horizontally-scaled `core` replicas (only one replica's INSERT wins per
-- day; every other replica's attempt fails the constraint and skips).
-- Lives in control_plane (not a per-tenant schema) - the job is
-- platform-wide, not scoped to any one tenant.
CREATE TABLE control_plane.platform_quota_reset_log (
    id UUID PRIMARY KEY,
    job_name VARCHAR(80) NOT NULL,
    reset_date DATE NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL,
    executed_by VARCHAR(255),
    CONSTRAINT uq_platform_quota_reset_log_job_date UNIQUE (job_name, reset_date)
);

CREATE INDEX idx_platform_quota_reset_log_job_date
    ON control_plane.platform_quota_reset_log (job_name, reset_date DESC);
