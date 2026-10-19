-- Platform-admin accuracy (2026-10-09) - the first "last activity" signal
-- anywhere in the platform. Until now an operator could not tell a tenant
-- that signed up yesterday and never came back from one used all day: the
-- roster had counts only, and nothing recorded when a person last made an
-- authenticated request.
--
-- Written by AppUserLastSeenRecorder (core/tenancy) from TenantContextFilter,
-- throttled to at most one UPDATE per user per 5 minutes (in-memory guard plus
-- the `last_seen_at < now() - interval '5 minutes'` predicate on the UPDATE
-- itself, so several replicas cannot make it chattier than that either).
-- Read by the platform roster (max per tenant) and the platform users list.
--
-- Additive + nullable: NULL means "not seen since this column existed", and
-- the running image never reads it, so this is expand-only (no gated marker).
ALTER TABLE control_plane.app_user
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_app_user_tenant_last_seen
    ON control_plane.app_user (tenant_id, last_seen_at);
