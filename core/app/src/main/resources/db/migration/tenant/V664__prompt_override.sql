-- V664: prompt_override + prompt_override_history - the prompt registry's stored wording at TENANT and AGENT scope
-- (core/prompts). Tenant schema: unqualified, no tenant_id (the schema is the tenancy). Shared Flyway namespace with
-- controlplane/, whose V665 holds the PLATFORM-scope twins.
--
-- prompt_override        the CURRENT override of one prompt key at one scope and locale. Resolution order is agent >
--                        tenant > platform > the default text shipped as a classpath resource (prompts/<locale>/<key>.md).
--   scope                'tenant' (scope_id is empty: the schema is the tenant) or 'agent' (scope_id is the agent's UUID).
--   prompt_key           the stable dotted key (directive.tool_trust, nudge.continue_unfinished, ...).
--   prompt_text          the wording. NULL only when enabled is false.
--   enabled              false = the prompt is switched OFF at this scope (a standing directive only; the service rejects
--                        it for a key that cannot be disabled).
--   version              1 for the first save, +1 for every change, a revert included; it continues after a revert.
-- prompt_override_history  every version ever saved, with author, reason and the wording before and after. IMMUTABLE:
--                        a trigger refuses UPDATE and DELETE (the same device as V75 for the audit log). A revert
--                        deletes the current row and appends a REVERT row; nothing is ever rewritten.
--
-- The nine tenant directives of V654 (prompt_directive_setting) are copied here as tenant-scope overrides so that no
-- tenant's prompt changes: a directive with two wordings today (full + compact, with + without restore) gets the same
-- override on both keys, exactly what the old single override did. prompt_directive_setting stays in place, unread.
CREATE TABLE IF NOT EXISTS prompt_override (
    scope        VARCHAR(16)  NOT NULL,
    scope_id     VARCHAR(64)  NOT NULL DEFAULT '',
    prompt_key   VARCHAR(128) NOT NULL,
    locale       VARCHAR(16)  NOT NULL DEFAULT 'en',
    prompt_text  TEXT         NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    version      INTEGER      NOT NULL,
    created_by   VARCHAR(255) NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, scope_id, prompt_key, locale),
    CONSTRAINT prompt_override_scope_chk CHECK (scope IN ('tenant', 'agent')),
    CONSTRAINT prompt_override_scope_id_chk CHECK (
        (scope = 'tenant' AND scope_id = '') OR (scope = 'agent' AND scope_id <> '')),
    CONSTRAINT prompt_override_text_chk CHECK (prompt_text IS NOT NULL OR NOT enabled),
    CONSTRAINT prompt_override_version_chk CHECK (version >= 1)
);

CREATE TABLE IF NOT EXISTS prompt_override_history (
    id               BIGSERIAL    PRIMARY KEY,
    scope            VARCHAR(16)  NOT NULL,
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
    CONSTRAINT prompt_override_history_scope_chk CHECK (scope IN ('tenant', 'agent')),
    CONSTRAINT prompt_override_history_action_chk CHECK (action IN ('CREATE', 'UPDATE', 'REVERT', 'MIGRATE')),
    CONSTRAINT prompt_override_history_version_uq UNIQUE (scope, scope_id, prompt_key, locale, version)
);

CREATE INDEX IF NOT EXISTS idx_prompt_override_history_lookup
    ON prompt_override_history (scope, scope_id, prompt_key, locale, created_at DESC);

CREATE OR REPLACE FUNCTION prompt_override_history_block_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'prompt_override_history is append-only: % is not permitted (row id=%)', TG_OP, OLD.id;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prompt_override_history_immutable ON prompt_override_history;
CREATE TRIGGER trg_prompt_override_history_immutable
    BEFORE UPDATE OR DELETE ON prompt_override_history
    FOR EACH ROW
    EXECUTE FUNCTION prompt_override_history_block_mutation();

-- Copy the tenant's directive settings (V654) as tenant-scope overrides, same wording and on/off state.
INSERT INTO prompt_override (scope, scope_id, prompt_key, locale, prompt_text, enabled, version, created_by, created_at)
SELECT 'tenant', '', m.new_key, 'en', s.text_override, s.enabled, 1, s.updated_by, s.updated_at
FROM prompt_directive_setting s
JOIN (VALUES
        ('current_date',        'directive.current_date'),
        ('memory_usage',        'directive.memory_usage'),
        ('media_honesty',       'directive.media_honesty'),
        ('tool_trust',          'directive.tool_trust'),
        ('tool_trust',          'directive.tool_trust.compact'),
        ('agentic_completion',  'directive.agentic_completion'),
        ('mcp_untrusted',       'directive.mcp_untrusted'),
        ('tool_search',         'directive.tool_search'),
        ('context_compaction',  'directive.context_compaction'),
        ('context_compaction',  'directive.context_compaction.no_restore'),
        ('browser_v2',          'directive.browser_v2'),
        ('artifacts',           'directive.artifacts'),
        ('custom_instructions', 'directive.custom_instructions')
     ) AS m(old_key, new_key) ON m.old_key = s.directive_key
WHERE s.enabled = FALSE OR s.text_override IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO prompt_override_history
    (scope, scope_id, prompt_key, locale, version, action, prompt_text, enabled, previous_text, previous_enabled,
     reason, created_by, created_at)
SELECT o.scope, o.scope_id, o.prompt_key, o.locale, o.version, 'MIGRATE', o.prompt_text, o.enabled, NULL, TRUE,
       'copied from prompt_directive_setting (V654)', o.created_by, o.created_at
FROM prompt_override o
WHERE o.scope = 'tenant'
ON CONFLICT DO NOTHING;
