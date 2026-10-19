package com.agenticai.core.agents.tools.stdiosandbox;

/**
 * {@code app.mcp.stdio.sandbox}: how a dynamic STDIO MCP server child process is confined when core
 * spawns it itself (the {@code mcp-stdio-sidecar} route, which already runs it elsewhere, is
 * independent of this).
 */
public enum StdioSandboxMode {
    /** Today's behaviour: the child runs as a plain subprocess of core. Default. */
    NONE,
    /**
     * OS facility: macOS {@code sandbox-exec} with a generated deny-default profile, Linux
     * bubblewrap (or, degraded, {@code unshare}). Windows is unsupported.
     */
    OS,
    /** {@code docker}/{@code podman run --rm} with a read-only root, dropped caps and limits. */
    CONTAINER
}
