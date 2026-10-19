package com.agenticai.core.mcp;

/**
 * Transport type for a registered MCP server, matching the three types the
 * PRD calls out. The registry records this for visibility/audit; the actual
 * transport wiring for the MVP happens in application-mcp.yml (see Stage 4
 * notes in McpServerRegistration), not dynamically from this table yet.
 */
public enum McpServerType {
    STDIO,
    SSE,
    STREAMABLE_HTTP
}
