-- "Agent / workflow as MCP server" (core/mcpserver, flag app.mcp-server.expose-agents.enabled,
-- default OFF): the per-resource opt-in that says "this agent / workflow may be advertised as its
-- own MCP tool". Nothing is exposed by default - a resource has no row until an editor flips the
-- switch, and even a row with exposed = TRUE is only ever listed to a caller whose
-- mcp_server_access_grant also lists that resource (two independent gates).
--
-- tool_name is allocated ONCE, the first time a resource is exposed ('agent_<slug>' /
-- 'workflow_<slug>', collision-suffixed), and kept for the life of the row so renaming an agent
-- never silently changes the tool name an external client has already wired up. It is unique
-- within the tenant schema (one namespace across both resource kinds). Tenant-scoped like every
-- other table here: isolation is the schema, there is no tenant column.
--
-- resource_id carries no FK on purpose: agents and workflows live in different tables and a
-- deleted resource must simply stop resolving (the catalog skips rows whose resource is gone).
-- Idempotent (IF NOT EXISTS, constraints inline) because tenant migrations re-run per schema.
CREATE TABLE IF NOT EXISTS mcp_tool_exposure (
    id UUID PRIMARY KEY,
    resource_type VARCHAR(16) NOT NULL,
    resource_id UUID NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    exposed BOOLEAN NOT NULL DEFAULT FALSE,
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_mcp_tool_exposure_resource UNIQUE (resource_type, resource_id),
    CONSTRAINT uq_mcp_tool_exposure_tool_name UNIQUE (tool_name)
);
