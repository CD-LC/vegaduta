package com.agenticai.core.mcp;

/**
 * Whether the deployment's STATIC ({@code application-mcp.yml}, "mcp" Spring profile) MCP wiring
 * can actually serve a connection's tools to agents right now: a static client with this
 * connection name exists AND is exposed by the static-exposure policy ({@code
 * StaticMcpExposurePolicy} in core/agents-tools hides every stateful shared server - filesystem,
 * memory, the browser servers - unless a platform flag opens them).
 *
 * <p>Implemented in core/agents-tools, the only module that can see the Spring AI {@code
 * McpSyncClient} list and the exposure policy; core/mcp is a leaf module and sees neither (the
 * same cross-module split {@link McpDynamicConnector} documents). {@code McpRegistryService} uses
 * it through an {@code ObjectProvider} to decide whether a name-only ("ready" catalog)
 * registration can stay a static reference or must upgrade to its own per-tenant dynamic launch
 * spec so that enabling it actually connects something.
 */
public interface McpStaticServerAvailability {

    /**
     * True when a static client named {@code connectionName} exists and is exposed to agents. A
     * deployment with no static wiring at all (no "mcp" profile, e.g. lite) answers false for
     * every name.
     */
    boolean usable(String connectionName);
}
