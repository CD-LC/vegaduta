package com.agenticai.core.mcp;

/**
 * WHICH step of establishing a dynamic MCP connection failed - the
 * structured half of the connection diagnosis (MCP-DOCTOR, 2026-07-25).
 * Persisted alongside {@code connectionError} and returned by
 * {@code POST /api/mcp/registrations/{id}/test} so the UI can show an
 * actionable cause ("this server needs credentials") instead of the SDK's
 * generic "Client failed to initialize by explicit API call".
 */
public enum McpConnectionPhase {
    /** STDIO only - the child process could not be spawned or died immediately (bad command, missing binary, launcher rejected). */
    SPAWN,
    /** Remote only - the HTTP layer rejected or never accepted the request (401/403/404, connection refused, DNS, TLS, SSRF-blocked URL). */
    HTTP,
    /** Transport came up but the MCP initialize/listTools exchange failed with a real error. */
    HANDSHAKE,
    /** Transport came up but the server never completed initialization within the timeout. */
    INIT_TIMEOUT,
    /** No failure - the connection test passed. */
    OK
}
