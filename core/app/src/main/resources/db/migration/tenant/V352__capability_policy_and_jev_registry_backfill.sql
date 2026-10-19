-- Generic Provider & Capability Registry (docs/architecture/GENERIC-PROVIDER-
-- CAPABILITY-REGISTRY-2026-08-09.md): wire the DECISION capability onto the
-- Phase-1 registry scaffolding instead of Jev's own brand-specific tables.
--
--   1) jev_credential (V333) rows move into provider_credential (V217) as
--      provider_key='TYPESAFE', capabilities={'DECISION'} - the same vendor
--      key name PlatformToolKey.TYPESAFE uses on the platform side.
--   2) jev_policy (V334) becomes capability_policy(capability='decision',
--      overrides jsonb) - the same three nullable knobs, keyed generically so
--      a second decision provider (or a second capability) needs no new table.
--
-- The legacy tables are intentionally NOT dropped: already-migrated tenants
-- keep them for rollback safety, and the entities that mapped them are gone
-- from the codebase. A later cleanup phase (the doc's Phase 5) can drop them.

INSERT INTO provider_credential (id, provider_key, capabilities, encrypted_secret, status, extra_config, created_at, updated_at)
SELECT id, 'TYPESAFE', ARRAY['DECISION']::varchar(32)[], encrypted_api_key, status, NULL, created_at, updated_at
FROM jev_credential
ON CONFLICT (provider_key) DO NOTHING;

CREATE TABLE IF NOT EXISTS capability_policy (
    id           UUID PRIMARY KEY,
    capability   VARCHAR(32) NOT NULL,
    overrides    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT capability_policy_capability_key UNIQUE (capability)
);

-- Nullable-override semantics are preserved by stripping NULL fields: an
-- absent key and an explicit NULL both mean "inherit the platform default."
INSERT INTO capability_policy (id, capability, overrides, created_at, updated_at)
SELECT
    id,
    'decision',
    jsonb_strip_nulls(jsonb_build_object(
        'costRouting',     cost_routing,
        'agentRouting',    agent_routing,
        'simpleThreshold', simple_threshold
    )),
    created_at,
    updated_at
FROM jev_policy
ON CONFLICT (capability) DO NOTHING;
