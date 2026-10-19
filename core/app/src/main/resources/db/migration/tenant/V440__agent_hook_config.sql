-- Declarative agent lifecycle hooks (core/agents com.agenticai.core.agents.hooks).
-- Flag app.agent-hooks.enabled (default OFF): with the flag off this table is
-- never read by the chat/tool path, so shipping the migration changes nothing.
--
-- WHY A TABLE AT ALL: `agent` has no per-agent JSON/settings column and there is
-- no generic tenant settings store, so a hook list has nowhere existing to live.
-- One small table holds BOTH scopes, keyed by scope_key:
--   'tenant'        -> the tenant-wide default hooks (agent_id NULL)
--   '<agent uuid>'  -> one agent's own hooks      (agent_id set)
-- hooks_json is the validated hook array (HookSchema), TEXT not JSONB for the same
-- reason agent_version.config_json is TEXT: it is only ever read and written whole.
-- settings_json carries tenant-scope settings that are not hooks (today only the
-- webhook host allow-list); it is '{}' for agent rows.
--
-- Tenant-schema table (unqualified, resolved via search_path): no tenant_id column,
-- and it dies with the tenant schema on erasure. ON DELETE CASCADE so deleting an
-- agent never leaves its hook configuration behind.
--
-- Version range V440-V441 is this feature's pre-assigned slice; V441 is unused.
CREATE TABLE IF NOT EXISTS agent_hook_config (
    scope_key      VARCHAR(64)  PRIMARY KEY,
    agent_id       UUID         REFERENCES agent(id) ON DELETE CASCADE,
    hooks_json     TEXT         NOT NULL DEFAULT '[]',
    settings_json  TEXT         NOT NULL DEFAULT '{}',
    updated_by     VARCHAR(255),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_hook_config_agent_id ON agent_hook_config (agent_id);
