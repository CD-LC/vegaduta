-- Master-Roadmap-2026.md item #5 (prompt-template versioning) - "cheapest
-- item in the whole roadmap": AgentService.updateSystemPrompt() and
-- Agent.setSystemPrompt() were pure overwrites with no history. This table
-- is purely additive - it does not change the Agent table at all, and it
-- carries no application-code migration risk since nothing reads from it
-- until AgentService starts writing to it in this same change.
--
-- One row is appended every time an agent's systemPrompt is about to be
-- overwritten (see AgentService#snapshotCurrentPrompt) - both on a normal
-- PATCH /prompt edit and immediately before a revert (a revert is itself
-- just another overwrite, so it gets snapshotted the same way - nothing is
-- ever lost, this table is genuinely append-only). The live/current prompt
-- always lives on agent.system_prompt itself; this table is history only,
-- there is no "current version" row.
--
-- No tenant_id column: schema-per-tenant multi-tenancy already isolates
-- this table, same as every other tenant-scoped table in this schema.
CREATE TABLE prompt_version (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    system_prompt TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_prompt_version_agent_id_created_at ON prompt_version (agent_id, created_at DESC);
