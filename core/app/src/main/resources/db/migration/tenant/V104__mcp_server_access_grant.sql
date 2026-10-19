-- Inbound MCP server hosting (core/mcpserver) - "an external MCP client
-- connects INTO this platform" per-tenant access grant. Lives in the TENANT
-- migration space (schema-per-tenant, same as workflow_definition/
-- mcp_server_registration) - one tenant's grants are only ever visible from
-- inside that tenant's own schema, exactly like every other tenant-owned
-- credential/config table.
--
-- Combined-migration-space check performed before picking V104: max was
-- V103 (tenant/V103__agent_code_workspace.sql) and V101
-- (controlplane/V101__plan_limits.sql) - both folders are validated as ONE
-- Flyway number space in this repo (a known recurring collision class), so
-- V104 is the next free number in either folder.
--
-- api_key_hash: SHA-256 hex digest of the generated raw API key - see
-- McpApiKeyHasher's javadoc for why this is hash-only (never reversible
-- encryption) unlike mcp_server_registration.encrypted_env. UNIQUE within
-- this tenant's schema; the CROSS-tenant uniqueness/lookup (which schema
-- does this hash belong to at all) is a separate concern handled by
-- control_plane.channel_account_link (ExternalAccountProvider.MCP_SERVER_KEY)
-- - the same two-table split WorkflowDefinition.webhook_token_hash /
-- channel_account_link(WORKFLOW_WEBHOOK) already uses.
--
-- exposed_agent_ids / exposed_workflow_ids / exposed_knowledge_collection_ids:
-- JSON arrays of UUIDs, the tenant-admin's explicit opt-in list - mirrors
-- Agent.allowed_knowledge_collection_ids's "empty/absent means nothing is
-- exposed, not everything" posture (the opposite default from that column's
-- OWN empty-means-unrestricted semantics - deliberately inverted here since
-- this is an external-facing grant, not an internal per-agent restriction).
-- Honest scope note: exposed_workflow_ids is persisted now but NOT YET
-- invoked by any MCP tool in this build - see McpServerToolConfig's javadoc.
CREATE TABLE mcp_server_access_grant (
    id UUID PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    api_key_hash VARCHAR(64) NOT NULL UNIQUE,
    api_key_display_prefix VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    exposed_agent_ids TEXT NOT NULL DEFAULT '[]',
    exposed_workflow_ids TEXT NOT NULL DEFAULT '[]',
    exposed_knowledge_collection_ids TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);
