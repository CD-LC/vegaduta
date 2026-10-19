package com.agenticai.core.mcp;

/**
 * Where a registration's actual runtime connection stands - separate from
 * {@code enabled} (an admin's "I want this on" preference) because wanting a
 * server on and it actually being reachable are different things once the
 * server is a real subprocess/network connection (#424, "how client can
 * configure and use it during runtime") rather than always-already-running
 * static YAML config.
 */
public enum McpConnectionStatus {
    /** No command/args/env ever configured - the row exists but nothing to connect to (or is a legacy metadata-only row predating dynamic connections). */
    NOT_CONFIGURED,
    /** Configured statically via application-mcp.yml at JVM startup, not through this dynamic path - see McpServerRegistration's javadoc. */
    STATIC,
    /** A connection attempt is in flight. */
    CONNECTING,
    /** Connected right now - DynamicMcpConnectionManager has a live client and its tools are available. */
    CONNECTED,
    /** Last connection attempt failed - see connectionError for why (bad credential, npx/Node missing, network unreachable, server crashed). */
    ERROR,
    /** Configured but the admin has turned it off (enabled = false) - no live connection is held. */
    DISABLED
}
