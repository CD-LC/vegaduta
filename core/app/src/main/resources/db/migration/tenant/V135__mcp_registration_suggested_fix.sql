-- MCP-SELF-HEAL persistence (2026-07-26). Previously the self-healer's
-- suggested fix (McpStdioSelfHealer, core/agents) only ever lived in the
-- ephemeral POST /{id}/test response, never persisted - so a registration-
-- time connect failure (the actual "Add from catalog" path) had nothing to
-- show but the raw error and no path forward. These three columns let both
-- the register-time failure AND every later GET /registrations list offer
-- "Apply suggested fix", not just a freshly-clicked /test call.
ALTER TABLE mcp_server_registration
    ADD COLUMN suggested_fix_command VARCHAR(255),
    ADD COLUMN suggested_fix_args TEXT,
    ADD COLUMN suggested_fix_reason TEXT;
