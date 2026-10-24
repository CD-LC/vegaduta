-- V668: setting_override + setting_override_history - the settings catalog's stored values at TENANT and AGENT scope
-- (core/settings). Tenant schema: unqualified, no tenant_id (the schema is the tenancy). Shared Flyway namespace with
-- controlplane/. PLATFORM values are not stored: they are the deployment's application.yml / environment.
--
-- setting_override          the CURRENT override of one catalog key at one scope. Resolution order is agent > tenant >
--                           platform (yml) > the code default; a security key takes the strictest of the layers.
--   scope                   'tenant' (scope_id is empty: the schema is the tenant) or 'agent' (scope_id is the agent's UUID).
--   setting_key             the catalog key (guardrails.failMode, chat.finish.maxContinuations, ...).
--   value                   the value as JSON (number, boolean or string), validated by the catalog on write.
--   version                 1 for the first save, +1 for every change, a reset included.
-- setting_override_history  every change ever made, with actor, reason, the value after and the value before. IMMUTABLE:
--                           a trigger refuses UPDATE and DELETE (the same device as V664 for prompt_override_history).
--
-- The seven agent.limits.* keys are NOT stored here: they keep living in tenant_runtime_limits and the agent table (V671),
-- exactly where /api/agents/{id}/runtime-limits reads and writes them; only their history rows land in the table below.
CREATE TABLE IF NOT EXISTS setting_override (
    scope        VARCHAR(16)  NOT NULL,
    scope_id     VARCHAR(64)  NOT NULL DEFAULT '',
    setting_key  VARCHAR(128) NOT NULL,
    value        JSONB        NOT NULL,
    version      INTEGER      NOT NULL,
    updated_by   VARCHAR(255) NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, scope_id, setting_key),
    CONSTRAINT setting_override_scope_chk CHECK (scope IN ('tenant', 'agent')),
    CONSTRAINT setting_override_scope_id_chk CHECK (
        (scope = 'tenant' AND scope_id = '') OR (scope = 'agent' AND scope_id <> '')),
    CONSTRAINT setting_override_version_chk CHECK (version >= 1)
);

CREATE TABLE IF NOT EXISTS setting_override_history (
    id              BIGSERIAL    PRIMARY KEY,
    scope           VARCHAR(16)  NOT NULL,
    scope_id        VARCHAR(64)  NOT NULL DEFAULT '',
    setting_key     VARCHAR(128) NOT NULL,
    version         INTEGER      NOT NULL,
    action          VARCHAR(16)  NOT NULL,
    value           JSONB        NULL,
    previous_value  JSONB        NULL,
    reason          VARCHAR(500) NULL,
    actor           VARCHAR(255) NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT setting_override_history_scope_chk CHECK (scope IN ('tenant', 'agent')),
    CONSTRAINT setting_override_history_action_chk CHECK (action IN ('SET', 'RESET')),
    CONSTRAINT setting_override_history_version_uq UNIQUE (scope, scope_id, setting_key, version)
);

CREATE INDEX IF NOT EXISTS idx_setting_override_history_lookup
    ON setting_override_history (scope, scope_id, setting_key, version DESC);

CREATE OR REPLACE FUNCTION setting_override_history_block_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'setting_override_history is append-only: % is not permitted (row id=%)', TG_OP, OLD.id;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_setting_override_history_immutable ON setting_override_history;
CREATE TRIGGER trg_setting_override_history_immutable
    BEFORE UPDATE OR DELETE ON setting_override_history
    FOR EACH ROW
    EXECUTE FUNCTION setting_override_history_block_mutation();
