-- Agent scratchpad: a persistent agent-authored notepad - free-form key/value
-- notes an agent writes for itself (core/agents-tools,
-- com.agenticai.core.agents.tools.scratchpad). Scoped per (agent_id,
-- session_id): a NULL session_id is an agent-long-term note visible from every
-- session of that agent, a non-NULL one belongs to that chat session only.
-- graphmemory extracts structured facts and workspace files are artifacts; this
-- is the agent's own free-form memory that survives turns.
--
-- TENANT-schema table (no schema qualifier, resolved via the connection's
-- search_path, same convention as custom_code_tool / browser_recipe): runs once
-- per tenant schema. No tenant_id column - schema-per-tenant means the schema
-- IS the tenancy.
--
-- Version V672: application.yml's flyway.locations merges controlplane/ and
-- tenant/ into ONE version stream, so a version number must be unique across
-- BOTH folders combined. V671 (tenant/V671__agent_runtime_limits.sql) was the
-- highest taken in either folder when this was written, re-checked directly
-- against the filesystem.
--
-- key/body are TEXT, byte_size records the body's UTF-8 length: the real bounds
-- (key shape, max-note-bytes, max-notes) are application-level
-- (app.tools.scratchpad.*), enforced by AgentScratchpadService so they can be
-- tuned without a schema change. byte_size is stored so list views can show and
-- the caps can audit sizes without re-decoding every body.
CREATE TABLE IF NOT EXISTS agent_scratchpad (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id    UUID         NOT NULL,
    -- NULL = agent-long-term note, visible from any of the agent's sessions.
    session_id  UUID,
    key         TEXT         NOT NULL,
    body        TEXT         NOT NULL,
    byte_size   INTEGER      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Uniqueness is on (agent_id, session_id, key) with NULL session_id treated as
-- a value, not "unknown" - an agent may hold exactly one long-term note named
-- 'todo'. A plain UNIQUE constraint would let two NULL-session rows share a
-- key (NULLs compare distinct), so COALESCE to the all-zero sentinel, the same
-- shape ux_browser_recipe_agent_name (V444) uses for its nullable agent_id.
CREATE UNIQUE INDEX IF NOT EXISTS ux_agent_scratchpad_scope_key
    ON agent_scratchpad (agent_id, (COALESCE(session_id, '00000000-0000-0000-0000-000000000000'::uuid)), key);

-- Backs AgentScratchpadService#list - an agent's notes for a session, newest
-- first, run on every turn the tool is offered, so it must never degrade into
-- a sequential scan as a tenant accumulates notes.
CREATE INDEX IF NOT EXISTS idx_agent_scratchpad_list
    ON agent_scratchpad (agent_id, updated_at DESC);
