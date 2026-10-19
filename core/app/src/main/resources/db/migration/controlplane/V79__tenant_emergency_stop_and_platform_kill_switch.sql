-- #815 Governance follow-up (Fail-safe: platform + per-tenant kill switch).
-- Renumbered from V76 to V79 - the tenant/ folder independently claimed V76
-- (ocr_provider_config.sql) around the same time, and this project's Flyway
-- setup merges controlplane/ and tenant/ into ONE combined version sequence
-- (see application.yml's flyway.locations comment) where version numbers
-- must be globally unique across both folders. tenant/ had already grown to
-- V78 (agent_share_memory.sql) by the time this collision was caught, so V79
-- is the first free slot past both folders' current highest version - same
-- fix as the V12/V13 and V60/V61 collisions documented in application.yml.
--
-- Two independent controls, both enforced by TenantStatusGuardImpl#isUsable
-- (see that class's javadoc for how one check covers both the JWT-
-- authenticated web console/API path AND, via AgentChatService#chat's own
-- explicit call, every omnichannel-webhook path that never goes through the
-- JWT filter):
--
-- 1. Per-tenant: tenant.emergency_stopped - a platform-admin incident-
--    response action against ONE tenant, deliberately separate from
--    tenant_status (whose values describe the provisioning lifecycle, and
--    nothing today ever sets SUSPENDED - conflating the two would make both
--    harder to reason about).
--
-- 2. Platform-wide: a new control_plane.platform_kill_switch singleton row
--    (same id=1L convention as control_plane.hosted_llm_pool_settings) -
--    stops EVERY tenant at once, for a genuine platform-level incident.

ALTER TABLE control_plane.tenant
    ADD COLUMN emergency_stopped BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN emergency_stop_reason VARCHAR(500),
    ADD COLUMN emergency_stopped_at TIMESTAMPTZ;

CREATE TABLE control_plane.platform_kill_switch (
    id          BIGINT PRIMARY KEY,
    stopped     BOOLEAN NOT NULL DEFAULT FALSE,
    reason      VARCHAR(500),
    stopped_at  TIMESTAMPTZ,
    updated_by  VARCHAR(255)
);

INSERT INTO control_plane.platform_kill_switch (id, stopped) VALUES (1, FALSE);
