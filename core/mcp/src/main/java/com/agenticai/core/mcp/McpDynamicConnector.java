package com.agenticai.core.mcp;

import java.util.UUID;

/**
 * The live-connection half of a dynamic MCP registration (#424) -
 * implemented by {@code DynamicMcpConnectionManager} in core/agents, not
 * here, since actually speaking the MCP client protocol needs {@code
 * spring-ai-starter-mcp-client} on the classpath, and this module stays a
 * dependency-free leaf (see this module's pom.xml). {@link
 * McpRegistryController} calls this through an {@code ObjectProvider} (same
 * cross-module-without-a-hard-dependency pattern {@code
 * WhatsAppOutputService} uses for {@code WhatsAppDispatchService}) right
 * after a dynamic registration is saved or toggled, so "save your API key"
 * and "the tool is now live" happen in the same request - no separate
 * restart, no separate "activate" step.
 */
public interface McpDynamicConnector {

    /** Attempts (or re-attempts) a real connection for this registration - updates its connectionStatus/connectionError as it goes. Safe to call even if already connected (reconnects). Never throws - failures are recorded on the registration, not propagated. */
    void connect(UUID registrationId);

    /** Closes and evicts any live connection for this registration - safe to call even if none exists. */
    void disconnect(UUID registrationId);

    /**
     * MCP-DOCTOR (2026-07-25): runs a real, bounded connect + initialize +
     * listTools + close against this registration (plus, for remotes, a cheap
     * HTTP pre-probe first), WITHOUT touching any live connection, and
     * persists the outcome (usageStatus READY/FAILED + structured diagnosis)
     * on the registration. Never throws - a failure is described in the
     * returned result, not propagated.
     */
    com.agenticai.core.mcp.dto.McpConnectionTestResult test(UUID registrationId);

    /**
     * Bug fix (2026-08-06): evicts {@code AgentToolProvider}'s short-TTL
     * cached MCP tool discovery for the CURRENT tenant (the ambient
     * TenantContext - must already be set on this thread), so the very next
     * agent turn - hosted or edge - re-discovers immediately instead of
     * serving a stale list for up to that cache's TTL. {@link #connect}/
     * {@link #disconnect} already call this internally for a DYNAMIC
     * registration; a STATIC (YAML-wired) registration's enable/disable
     * toggle has no live connection to (re)establish, so {@code
     * McpRegistryController} must call this directly for that case. Never
     * throws.
     */
    void evictToolCache();
}
