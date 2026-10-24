-- V665: platform_prompt_override + platform_prompt_override_history - the prompt registry's PLATFORM-scope wording
-- (core/prompts). Control plane, not a tenant schema: a platform override applies to every tenant of the install and is
-- written by a platform admin only. Shared Flyway namespace with tenant/, whose V664 holds the tenant- and agent-scope
-- twins and explains the columns and the resolution order (agent > tenant > platform > classpath default).
--
-- Same shape as the tenant tables; scope is always 'platform' and scope_id always empty. The history is IMMUTABLE: a
-- trigger refuses UPDATE and DELETE. No data is migrated: nothing was ever configurable platform-wide before.
CREATE TABLE IF NOT EXISTS control_plane.platform_prompt_override (
    scope        VARCHAR(16)  NOT NULL DEFAULT 'platform',
    scope_id     VARCHAR(64)  NOT NULL DEFAULT '',
    prompt_key   VARCHAR(128) NOT NULL,
    locale       VARCHAR(16)  NOT NULL DEFAULT 'en',
    prompt_text  TEXT         NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    version      INTEGER      NOT NULL,
    created_by   VARCHAR(255) NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, scope_id, prompt_key, locale),
    CONSTRAINT platform_prompt_override_scope_chk CHECK (scope = 'platform' AND scope_id = ''),
    CONSTRAINT platform_prompt_override_text_chk CHECK (prompt_text IS NOT NULL OR NOT enabled),
    CONSTRAINT platform_prompt_override_version_chk CHECK (version >= 1)
);

CREATE TABLE IF NOT EXISTS control_plane.platform_prompt_override_history (
    id               BIGSERIAL    PRIMARY KEY,
    scope            VARCHAR(16)  NOT NULL DEFAULT 'platform',
    scope_id         VARCHAR(64)  NOT NULL DEFAULT '',
    prompt_key       VARCHAR(128) NOT NULL,
    locale           VARCHAR(16)  NOT NULL DEFAULT 'en',
    version          INTEGER      NOT NULL,
    action           VARCHAR(16)  NOT NULL,
    prompt_text      TEXT         NULL,
    enabled          BOOLEAN      NOT NULL,
    previous_text    TEXT         NULL,
    previous_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    reason           VARCHAR(500) NULL,
    created_by       VARCHAR(255) NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT platform_prompt_override_history_scope_chk CHECK (scope = 'platform' AND scope_id = ''),
    CONSTRAINT platform_prompt_override_history_action_chk CHECK (action IN ('CREATE', 'UPDATE', 'REVERT', 'MIGRATE')),
    CONSTRAINT platform_prompt_override_history_version_uq UNIQUE (scope, scope_id, prompt_key, locale, version)
);

CREATE INDEX IF NOT EXISTS idx_platform_prompt_override_history_lookup
    ON control_plane.platform_prompt_override_history (scope, scope_id, prompt_key, locale, created_at DESC);

CREATE OR REPLACE FUNCTION control_plane.platform_prompt_override_history_block_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'platform_prompt_override_history is append-only: % is not permitted (row id=%)', TG_OP, OLD.id;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_platform_prompt_override_history_immutable ON control_plane.platform_prompt_override_history;
CREATE TRIGGER trg_platform_prompt_override_history_immutable
    BEFORE UPDATE OR DELETE ON control_plane.platform_prompt_override_history
    FOR EACH ROW
    EXECUTE FUNCTION control_plane.platform_prompt_override_history_block_mutation();
