-- Platform-admin controls (2026-10-09), two additive pieces in one migration.
--
-- 1. Per-tenant monthly HOSTED-POOL spend cap (calendar month, UTC). BYOK traffic is never counted or blocked.
--    spend_cap_usd          NULL = no cap (the default for every existing tenant: no behaviour change).
--    spend_cap_alert_stage  highest alert already sent this month: 0 = none, 80, 100.
--    spend_cap_alert_month  first day (UTC) of the month spend_cap_alert_stage belongs to; a different month
--                           reads as stage 0, so alerts re-arm on the 1st without a reset job.
--    Written only by SpendCapService (core/agents) with plain JDBC - the Tenant entity does not map these
--    columns, so Hibernate validation and every existing tenant save are unaffected.
ALTER TABLE control_plane.tenant
    ADD COLUMN IF NOT EXISTS spend_cap_usd NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS spend_cap_alert_stage SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS spend_cap_alert_month DATE;

-- 2. Read-only "view as user" sessions for platform admins (ImpersonationService, core/platform).
--    grant_hash is the SHA-256 (hex) of a 32-byte random grant handed to the admin's browser exactly once;
--    the raw grant is never stored. target_roles is the target's realm roles captured at start (comma
--    separated) so the request path never calls Keycloak. request_count/last_request_at are flushed in
--    batches (~30 s), not written per request.
CREATE TABLE IF NOT EXISTS control_plane.impersonation_session (
    id                  UUID PRIMARY KEY,
    admin_keycloak_id   VARCHAR(255) NOT NULL,
    admin_email         VARCHAR(255),
    tenant_id           UUID NOT NULL,
    tenant_schema       VARCHAR(63) NOT NULL,
    target_keycloak_id  VARCHAR(255) NOT NULL,
    target_email        VARCHAR(255),
    target_roles        TEXT NOT NULL DEFAULT '',
    reason              TEXT NOT NULL,
    grant_hash          VARCHAR(64) NOT NULL,
    started_at          TIMESTAMPTZ NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    ended_at            TIMESTAMPTZ,
    request_count       INTEGER NOT NULL DEFAULT 0,
    last_request_at     TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_impersonation_session_grant_hash
    ON control_plane.impersonation_session (grant_hash);
CREATE INDEX IF NOT EXISTS idx_impersonation_session_admin_active
    ON control_plane.impersonation_session (admin_keycloak_id, expires_at)
    WHERE ended_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_impersonation_session_started
    ON control_plane.impersonation_session (started_at DESC);
