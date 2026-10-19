-- Autonomy/Guardrails Strategy Stage A: opt-in per-agent tool allow-list.
--
-- Deliberately opt-in, not a strict default-deny: this table pairs with a
-- future dynamic-tool-selection UI (Strategy doc Stage B, not built yet) -
-- until that UI exists, a tenant-admin has no discoverable way to grant a
-- tool back to an agent once denied, so flipping the *default* to deny-all
-- today would silently break every existing agent's already-working MCP/
-- knowledge-base tools with no way to fix it except direct SQL. Empty (no
-- rows for a given agent_id) means "no restriction - allow every tool
-- normally resolved for this agent", exactly today's behavior. A tenant-
-- admin who wants a stricter posture *right now*, ahead of Stage B's UI,
-- can already do so by inserting rows directly - AgentToolProvider (see its
-- Java-side comment) filters against this table whenever it has any rows
-- for the agent.
CREATE TABLE agent_allowed_tool (
    agent_id  UUID NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    tool_name VARCHAR(255) NOT NULL,
    PRIMARY KEY (agent_id, tool_name)
);
