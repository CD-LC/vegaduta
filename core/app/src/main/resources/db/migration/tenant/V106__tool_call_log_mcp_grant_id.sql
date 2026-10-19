-- Data-governance audit fix (2026-07-20) - the real, queryable fix for the gap flagged during
-- tonight's data-governance audit of core/mcpserver (inbound MCP server hosting): there was no
-- column anywhere identifying "this tool call happened because an external MCP client, via
-- grant X, triggered it." The stopgap already shipped (McpApiKeyAuthFilter's TenantContext
-- subject, McpCallAgentToolCallback's session_id prefix) is a human-readable breadcrumb, not a
-- queryable fact - this column is.
--
-- Nullable, no FK to mcp_server_access_grant: this is an append-only audit trail
-- (tool_call_log's own existing rows are never updated/deleted either - see LoggingToolCallback),
-- and a row here must remain queryable/attributable even after the grant that caused it is later
-- revoked or rotated (McpServerAccessGrantService#revoke/#rotate) - the same "keep the historical
-- trail even after the credential is gone" posture audit_log_entry.actor_id already takes (see
-- that entity's own javadoc).
--
-- Combined-migration-space check performed immediately before picking this number (per this
-- build's own recurring collision class - see V104__mcp_server_access_grant.sql's own note):
-- max was V105 (tenant/V105__approval_request_execution_result.sql) and V101
-- (controlplane/V101__plan_limits.sql) - both folders share one Flyway number space in this repo,
-- so V106 is the next free number in either folder.
ALTER TABLE tool_call_log
    ADD COLUMN mcp_grant_id UUID;

-- Backs ToolCallLogRepository#findByMcpGrantIdOrderByStartedAtDesc - "show me every action this
-- platform has taken as a result of MCP grant X" needs to be a fast, indexed lookup for a real
-- incident investigation or compliance audit, not a sequential scan over every tenant's whole
-- tool-call history.
CREATE INDEX idx_tool_call_log_mcp_grant_id ON tool_call_log (mcp_grant_id) WHERE mcp_grant_id IS NOT NULL;
