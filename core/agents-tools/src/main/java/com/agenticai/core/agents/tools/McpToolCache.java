package com.agenticai.core.agents.tools;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * The per-tenant table of live tool callbacks and everything that rebuilds or filters it: wrapping
 * raw SDK callbacks (sanitize + namespace), the readiness-gate filter, and the tools/list_changed
 * refresh. The maps are owned by {@link DynamicMcpConnectionManager} (tenant schema first,
 * registration id second - the #596 isolation shape) and shared here by reference.
 */
final class McpToolCache {

    private static final Logger log = LoggerFactory.getLogger(McpToolCache.class);
    private static final String TOOLS_REFRESH_ACTOR = "system:mcp-tools-refresh";

    /** Outermost wrap (routing + pin observation); supplied by the manager. */
    @FunctionalInterface
    interface Routable {
        List<ToolCallback> apply(List<ToolCallback> wrapped, String tenant, UUID registrationId);
    }

    private final Map<String, Map<UUID, McpSyncClient>> liveClients;
    private final Map<String, Map<UUID, List<ToolCallback>>> liveToolCallbacks;
    private final Function<McpSyncClient, ToolCallback[]> fetcher;
    private final Routable routable;
    private final Runnable evictToolCache;

    McpToolCache(
            Map<String, Map<UUID, McpSyncClient>> liveClients,
            Map<String, Map<UUID, List<ToolCallback>>> liveToolCallbacks,
            Function<McpSyncClient, ToolCallback[]> fetcher,
            Routable routable,
            Runnable evictToolCache) {
        this.liveClients = liveClients;
        this.liveToolCallbacks = liveToolCallbacks;
        this.fetcher = fetcher;
        this.routable = routable;
        this.evictToolCache = evictToolCache;
    }

    /**
     * Every advertised callback is wrapped with the discovery/result caps and then namespaced under
     * mcp__&lt;server&gt;__&lt;tool&gt; (Pentest L4). Shared by connect() and the refresh.
     */
    static List<ToolCallback> wrapDynamicCallbacks(ToolCallback[] raw, String serverName) {
        if (raw == null) {
            return List.of();
        }
        return List.of(raw).stream()
                .map(cb -> (ToolCallback) new SanitizedMcpToolCallback(cb))
                .map(cb -> (ToolCallback) new NamespacedMcpToolCallback(cb, serverName))
                .toList();
    }

    /**
     * Pure half of the readiness gate: only live callbacks whose registration id is in {@code
     * usable} are returned (debug-logged otherwise so "enabled but no tools" is diagnosable).
     */
    static List<ToolCallback> filterUsable(
            Map<UUID, List<ToolCallback>> liveByRegistration, Set<UUID> usable) {
        List<ToolCallback> all = new ArrayList<>();
        liveByRegistration.forEach(
                (registrationId, callbacks) -> {
                    if (usable.contains(registrationId)) {
                        all.addAll(callbacks);
                    } else {
                        log.debug(
                                "Skipping live MCP registration {} - usageStatus is not READY/ACKNOWLEDGED (readiness gate); "
                                        + "run POST /api/mcp/registrations/{}/test or acknowledge it to let agents use its tools",
                                registrationId,
                                registrationId);
                    }
                });
        return all;
    }

    /** Owner side of a routed tool call: runs {@code toolName} on this JVM's live callback. */
    String callLiveTool(String tenant, UUID registrationId, String toolName, String argsJson) {
        List<ToolCallback> cbs =
                liveToolCallbacks.getOrDefault(tenant, Map.of()).get(registrationId);
        if (cbs != null) {
            for (ToolCallback cb : cbs) {
                if (cb.getToolDefinition().name().equals(toolName)) {
                    return cb.call(argsJson);
                }
            }
        }
        throw new IllegalStateException(
                "No live MCP tool '"
                        + toolName
                        + "' for registration "
                        + registrationId
                        + " on this replica");
    }

    /** The live client of one tenant's registration, or null (tenant-first: no cross-tenant read). */
    McpSyncClient liveClient(String tenant, UUID registrationId) {
        Map<UUID, McpSyncClient> tenantClients = tenant == null ? null : liveClients.get(tenant);
        return tenantClients == null || registrationId == null
                ? null
                : tenantClients.get(registrationId);
    }

    boolean isCurrentLiveClient(String tenant, UUID registrationId, McpSyncClient client) {
        Map<UUID, McpSyncClient> tenantClients = liveClients.get(tenant);
        return client != null
                && tenantClients != null
                && tenantClients.get(registrationId) == client;
    }

    /**
     * {@code toolsChangeConsumer} target (2026-09-28). The SDK invokes the consumer on its own
     * notification thread; re-listing synchronously there would block the very thread that delivers
     * the listTools response, so the rebuild hops to a short-lived daemon thread. The tenant is
     * passed explicitly and re-applied in {@link #onToolsListChanged}.
     */
    void scheduleToolsRefresh(
            String tenant, UUID registrationId, McpSyncClient client, String serverName) {
        if (client == null) {
            return;
        }
        TenantAwareExecutors.startThread(
                "mcp-tools-refresh-" + tenant + "-" + registrationId,
                true,
                () -> {
                            try {
                                onToolsListChanged(tenant, registrationId, client, serverName);
                            } catch (Throwable t) {
                                log.warn(
                                        "MCP tools/list_changed refresh for '{}' ({}) failed for tenant '{}'",
                                        serverName,
                                        registrationId,
                                        tenant,
                                        t);
                            }
                        });
    }

    /**
     * Rebuilds ONE live connection's callbacks after the server announced a tool-list change, then
     * invalidates the agent-side discovery cache. No-op if the registration was
     * disconnected/reconnected meanwhile; a failed re-list keeps the previous callbacks.
     */
    void onToolsListChanged(
            String tenant, UUID registrationId, McpSyncClient client, String serverName) {
        if (!isCurrentLiveClient(tenant, registrationId, client)) {
            log.debug(
                    "Ignoring tools/list_changed for MCP registration {} - no longer the live client",
                    registrationId);
            return;
        }
        List<ToolCallback> rebuilt;
        try {
            rebuilt =
                    routable.apply(
                            wrapDynamicCallbacks(fetcher.apply(client), serverName),
                            tenant,
                            registrationId);
        } catch (Throwable t) {
            log.warn(
                    "Could not re-list tools for MCP registration '{}' ({}) after tools/list_changed - keeping the previous tool list",
                    serverName,
                    registrationId,
                    t);
            return;
        }
        if (!isCurrentLiveClient(tenant, registrationId, client)) {
            return;
        }
        liveToolCallbacks
                .computeIfAbsent(tenant, t -> new ConcurrentHashMap<>())
                .put(registrationId, rebuilt);
        log.info(
                "MCP dynamic connection '{}' ({}) for tenant '{}' refreshed its tool list - {} tool(s)",
                serverName,
                registrationId,
                tenant,
                rebuilt.size());
        // evictToolCache() keys the agent-side dynamic cache off TenantContext - this runs on the
        // refresh thread, so apply this connection's tenant for the eviction and restore after.
        TenantContext previous = TenantContext.current().orElse(null);
        TenantContext.set(TenantContext.of(tenant, TOOLS_REFRESH_ACTOR, Set.of()));
        try {
            evictToolCache.run();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }
}
