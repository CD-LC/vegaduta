package com.agenticai.core.mcp;

/**
 * Readiness gate for a dynamic MCP registration (MCP-DOCTOR, 2026-07-25) -
 * separate from {@link McpConnectionStatus} on purpose: connectionStatus
 * tracks the LIVE connection ("is a client holding this open right now"),
 * usageStatus tracks VERIFICATION ("has this server ever passed a real
 * connect+initialize test since it was last (re)configured"). Agents only
 * ever receive tools from registrations whose usageStatus is {@link #READY}
 * or {@link #ACKNOWLEDGED} - an enabled-but-unverified server contributes
 * ZERO tools to the agent loop (defense-in-depth against tool-description
 * poisoning from a server nobody has ever seen answer correctly). See
 * {@code DynamicMcpConnectionManager.resolveDynamicToolCallbacks} for the
 * enforcement point.
 */
public enum McpUsageStatus {
    /** Never tested since creation or last config change - the default. Agents will not use it. */
    UNTESTED,
    /** Last test (or auto-test on enable/update) succeeded - agents may use its tools. */
    READY,
    /** Last test failed - agents will not use it until a test passes or an admin acknowledges. */
    FAILED,
    /** An admin explicitly overrode a FAILED/UNTESTED state ("use it anyway") - agents may use its tools. */
    ACKNOWLEDGED
}
