-- #424 ("Dynamic MCP servers + user-created tools") - the user-created-tools
-- half. Scoped deliberately: a tenant-authored SpEL expression, not an
-- arbitrary-language script engine - see UserToolScript's javadoc for why
-- (no sandboxed script runtime exists anywhere in this codebase, and SpEL's
-- SimpleEvaluationContext is sandboxed by construction - no method/
-- constructor/bean resolution, no loop constructs in the grammar at all).
CREATE TABLE user_tool_script (
    id UUID PRIMARY KEY,
    tool_name VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    description TEXT,
    expression TEXT NOT NULL,
    input_schema_json TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_user_tool_script_tool_name UNIQUE (tool_name)
);

CREATE INDEX idx_user_tool_script_enabled ON user_tool_script (enabled);
