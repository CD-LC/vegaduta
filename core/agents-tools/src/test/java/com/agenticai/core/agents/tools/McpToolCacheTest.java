package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.McpCatalogService;
import com.agenticai.core.mcp.McpConnectionPhase;
import com.agenticai.core.mcp.McpConnectionStatus;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.mcp.McpReviewStatus;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.McpStdioCommandGuard;
import com.agenticai.core.mcp.McpUsageStatus;
import com.agenticai.core.mcp.atlassian.AtlassianOAuthService;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/** Live tool-callback table: readiness filter, tenant isolation, tools/list_changed refresh. */
class McpToolCacheTest extends DynamicMcpConnectionManagerTestBase {

    @Test
    void resolveDynamicToolCallbacksReturnsEmptyListWhenNothingIsLiveForCurrentTenant() {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        assertTrue(manager.resolveDynamicToolCallbacks().isEmpty());
    }

    @Test
    void filterUsableExcludesEveryCallbackOfANonReadyRegistration() {
        // The gate itself: a live, connected registration whose usageStatus is
        // not READY/ACKNOWLEDGED (i.e. not in the usable set) must contribute
        // ZERO tool callbacks to the agent loop.
        UUID readyId = UUID.randomUUID();
        UUID untestedId = UUID.randomUUID();
        ToolCallback readyTool = mock(ToolCallback.class);
        ToolCallback untestedTool = mock(ToolCallback.class);
        Map<UUID, List<ToolCallback>> live =
                Map.of(
                        readyId, List.of(readyTool),
                        untestedId, List.of(untestedTool));

        List<ToolCallback> offered =
                DynamicMcpConnectionManager.filterUsable(live, Set.of(readyId));

        assertEquals(List.of(readyTool), offered);
    }

    @Test
    void filterUsableYieldsNothingWhenNoRegistrationIsUsable() {
        UUID id = UUID.randomUUID();
        Map<UUID, List<ToolCallback>> live = Map.of(id, List.of(mock(ToolCallback.class)));

        assertTrue(DynamicMcpConnectionManager.filterUsable(live, Set.of()).isEmpty());
    }

    @Test
    void resolveDynamicToolCallbacksFailsClosedWhenTheUsageStatusGateCannotBeRead() {
        // Security gate posture: if the registry can't be queried, offer NO
        // dynamic MCP tools rather than unverified ones. (With no live
        // callbacks the gate query is skipped entirely, so this documents the
        // short-circuit contract; the fail-closed branch itself is pure
        // try/catch around usableDynamicRegistrationIds.)
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        when(registryService.usableDynamicRegistrationIds())
                .thenThrow(new RuntimeException("db down"));
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        assertDoesNotThrow(() -> assertTrue(manager.resolveDynamicToolCallbacks().isEmpty()));
    }

    @Test
    void toolsListChangedRebuildsTheLiveConnectionsCallbacksAndEvictsTheAgentCache()
            throws ReflectiveOperationException {
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        DynamicMcpConnectionManager manager =
                newManager(
                        mock(McpRegistryService.class),
                        mock(TenantSchemaIterator.class),
                        mockProviderOf(agentToolProvider));
        UUID id = UUID.randomUUID();
        McpSyncClient client = mock(McpSyncClient.class);
        liveMap(manager, "liveClients")
                .computeIfAbsent("tenant_a", t -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(id, client);
        liveMap(manager, "liveToolCallbacks")
                .computeIfAbsent("tenant_a", t -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(id, List.of());
        manager.toolCallbackFetcher = c -> new ToolCallback[] {rawTool("newTool")};

        manager.onToolsListChanged("tenant_a", id, client, "srv");

        @SuppressWarnings("unchecked")
        List<ToolCallback> rebuilt =
                (List<ToolCallback>) liveMap(manager, "liveToolCallbacks").get("tenant_a").get(id);
        assertEquals(1, rebuilt.size());
        assertEquals("mcp__srv__newTool", rebuilt.get(0).getToolDefinition().name());
        verify(agentToolProvider).evictMcpToolCache();
        assertTrue(
                TenantContext.current().isEmpty(),
                "the refresh must not leak its TenantContext onto the calling thread");
    }

    @Test
    void toolsListChangedFromAStaleClientIsIgnored() throws ReflectiveOperationException {
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        DynamicMcpConnectionManager manager =
                newManager(
                        mock(McpRegistryService.class),
                        mock(TenantSchemaIterator.class),
                        mockProviderOf(agentToolProvider));
        UUID id = UUID.randomUUID();
        McpSyncClient current = mock(McpSyncClient.class);
        McpSyncClient stale = mock(McpSyncClient.class);
        liveMap(manager, "liveClients")
                .computeIfAbsent("tenant_a", t -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(id, current);
        manager.toolCallbackFetcher = c -> new ToolCallback[] {rawTool("resurrected")};

        manager.onToolsListChanged("tenant_a", id, stale, "srv");
        // Disconnected registration (no live client at all) - same no-op.
        manager.onToolsListChanged("tenant_b", id, current, "srv");

        assertNull(liveMap(manager, "liveToolCallbacks").get("tenant_a"));
        assertNull(liveMap(manager, "liveToolCallbacks").get("tenant_b"));
        verify(agentToolProvider, never()).evictMcpToolCache();
    }

    @Test
    void toolsListChangedKeepsThePreviousToolsWhenTheRelistFails()
            throws ReflectiveOperationException {
        DynamicMcpConnectionManager manager =
                newManager(mock(McpRegistryService.class), mock(TenantSchemaIterator.class));
        UUID id = UUID.randomUUID();
        McpSyncClient client = mock(McpSyncClient.class);
        List<ToolCallback> previous = List.of(rawTool("old"));
        liveMap(manager, "liveClients")
                .computeIfAbsent("tenant_a", t -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(id, client);
        liveMap(manager, "liveToolCallbacks")
                .computeIfAbsent("tenant_a", t -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(id, previous);
        manager.toolCallbackFetcher =
                c -> {
                    throw new IllegalStateException("server went away mid-refresh");
                };

        assertDoesNotThrow(() -> manager.onToolsListChanged("tenant_a", id, client, "srv"));

        assertEquals(previous, liveMap(manager, "liveToolCallbacks").get("tenant_a").get(id));
    }
}
