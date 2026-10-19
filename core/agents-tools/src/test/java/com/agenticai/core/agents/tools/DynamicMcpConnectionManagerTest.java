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

/** Connection lifecycle: connect/disconnect/health-check/evict through the manager. */
class DynamicMcpConnectionManagerTest extends DynamicMcpConnectionManagerTestBase {

    @Test
    void connectSkipsAllRegistryWritesWhenNoDynamicConfigExists() {
        // #connect's first real branch: registryService.dynamicConfigFor(id) empty means
        // "not dynamic, or deleted" - connect() must log and return without ever calling
        // markConnecting/markConnected/markError, and without throwing (interface javadoc:
        // "Never throws - failures are recorded on the registration, not propagated").
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        when(registryService.dynamicConfigFor(id)).thenReturn(Optional.empty());

        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        assertDoesNotThrow(() -> manager.connect(id));

        verify(registryService, never()).markConnecting(any());
        verify(registryService, never()).markConnected(any());
        verify(registryService, never()).markError(any(), any());
        assertTrue(manager.resolveDynamicToolCallbacks().isEmpty());
    }

    @Test
    void disconnectIsANoOpWhenNothingIsConnectedForTheCurrentTenant() {
        // disconnect() must be safe to call even if this registration was never connected
        // (or already disconnected) - per its own javadoc ("safe to call even if none
        // exists") - and must never touch registryService at all (it only owns the live
        // McpSyncClient map, not connectionStatus persistence).
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        assertDoesNotThrow(() -> manager.disconnect(UUID.randomUUID()));

        verify(registryService, never()).markError(any(), any());
    }

    // --- Bug fix (2026-08-06): "once tools enabled in mcp, agents still unable to access it" ---
    // Root cause was AgentToolProvider's cache never being evicted on enable/disable - these
    // tests lock in evictToolCache() itself and its call from disconnect()'s early-exit path
    // (the one part of connect()/disconnect() this suite can reach without the reflective SDK
    // call - see class javadoc).

    @Test
    void evictToolCacheCallsThroughToAgentToolProviderWhenOneIsAvailable() {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        ObjectProvider<AgentToolProvider> provider = mockProviderOf(agentToolProvider);
        DynamicMcpConnectionManager manager =
                newManager(registryService, tenantSchemaIterator, provider);

        manager.evictToolCache();

        verify(agentToolProvider).evictMcpToolCache();
    }

    @Test
    void evictToolCacheNeverThrowsWhenNoAgentToolProviderBeanExists() {
        // A degraded/slim context (no AgentToolProvider bean at all) must not break MCP
        // registry actions over a cache-freshness nicety - see evictToolCache's own javadoc.
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        assertDoesNotThrow(manager::evictToolCache);
    }

    @Test
    void disconnectEvictsTheAgentToolProviderCacheEvenWhenNothingWasLive() {
        // The exact bug: disabling a registration that had no live client (or a static
        // registration's toggle, which routes through here indirectly via evictToolCache
        // being called unconditionally) must still refresh the cache immediately.
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        DynamicMcpConnectionManager manager =
                newManager(
                        registryService, tenantSchemaIterator, mockProviderOf(agentToolProvider));

        manager.disconnect(UUID.randomUUID());

        verify(agentToolProvider).evictMcpToolCache();
    }

    /**
     * The health check's failure path used to hand-remove the dead registration from the three live
     * maps only - never {@code closeGracefully()} (orphaning the hung subprocess), never {@code
     * evictToolCache()} (so the dead server's tools kept being offered for up to the
     * discovery-cache TTL, the exact mid-conversation failure the method's own javadoc says it
     * exists to prevent), never removing the stderr tail. It now routes through {@code
     * disconnect()}, which does the full teardown.
     */
    @Test
    void healthCheckFullyTearsDownADeadClientViaDisconnectNotJustMapRemoval() throws Exception {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DynamicMcpConnectionManager manager =
                newManager(
                        registryService,
                        tenantSchemaIterator,
                        mockProviderOf(agentToolProvider),
                        mock(AtlassianOAuthService.class),
                        meterRegistry);

        UUID registrationId = UUID.randomUUID();
        McpSyncClient deadClient = mock(McpSyncClient.class);
        when(deadClient.listTools()).thenThrow(new RuntimeException("subprocess died"));

        Field liveClientsField = DynamicMcpConnectionManager.class.getDeclaredField("liveClients");
        liveClientsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Map<UUID, McpSyncClient>> liveClients =
                (Map<String, Map<UUID, McpSyncClient>>) liveClientsField.get(manager);
        java.util.concurrent.ConcurrentHashMap<UUID, McpSyncClient> tenantClients =
                new java.util.concurrent.ConcurrentHashMap<>();
        tenantClients.put(registrationId, deadClient);
        liveClients.put("test_tenant", tenantClients);

        // Mirror the real iterator (TenantSchemaIterator#forEachActiveTenantSchema sets
        // TenantContext around the callback and clears after) so disconnect()'s tenantKey()
        // resolves to the same schema key the seeded map uses.
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            java.util.function.Consumer<String> action = invocation.getArgument(0);
                            TenantContext.set(
                                    TenantContext.of(
                                            "test_tenant", "system:health-check-test", Set.of()));
                            try {
                                action.accept("test_tenant");
                            } finally {
                                TenantContext.clear();
                            }
                            return null;
                        })
                .when(tenantSchemaIterator)
                .forEachActiveTenantSchema(any());

        manager.healthCheckLiveConnections();

        verify(deadClient).closeGracefully();
        verify(agentToolProvider).evictMcpToolCache();
        verify(registryService)
                .markErrorDiagnosis(
                        eq(registrationId),
                                org.mockito.ArgumentMatchers.contains("Connection lost"),
                        eq(McpConnectionPhase.HANDSHAKE), any());
        assertTrue(
                liveClients.getOrDefault("test_tenant", Map.of()).isEmpty(),
                "the dead client's registration must be gone from liveClients");
        assertEquals(1.0, meterRegistry.counter("mcp.connection.lost").count());
    }

    @Test
    void connectNeverThrowsEvenWhenRegistryServiceFails() {
        // Regression test for a previously-locked-in KNOWN BUG: McpDynamicConnector
        // #connect's javadoc (core/mcp) promises "Never throws - failures are recorded on
        // the registration, not propagated." disconnect(registrationId),
        // registryService.dynamicConfigFor(registrationId), and
        // registryService.markConnecting(registrationId) used to run BEFORE the method's
        // try { ... } catch (Throwable t) block started, so a real-world failure in
        // markConnecting (e.g. a DB/transaction error, an optimistic-locking exception)
        // propagated straight out of connect() instead of being recorded via markError -
        // and since connect() is called synchronously from
        // McpRegistryController.register()/toggle() mid-HTTP-request (per this class's own
        // javadoc), that turned into an unhandled 500 instead of a clean connectionError on
        // the registration. All three calls now run inside the same try/catch(Throwable) as
        // the rest of connect(), so this same markConnecting failure must be swallowed and
        // recorded via markError instead of propagating.
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpDynamicConfig config =
                new McpDynamicConfig(
                        id, "flaky-server", McpServerType.STDIO, "npx", List.of(), Map.of(), null);
        when(registryService.dynamicConfigFor(id)).thenReturn(Optional.of(config));
        doThrow(new RuntimeException("db unavailable")).when(registryService).markConnecting(id);

        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        assertDoesNotThrow(() -> manager.connect(id));

        // The registration must end up in an error state, not silently stuck in whatever
        // status it was in before - markConnecting's own failure is itself the thing that
        // gets recorded. (MCP-DOCTOR: failures are now persisted through the
        // structured markErrorDiagnosis variant rather than plain markError.
        // MCP-SELF-HEAL: connect() now always calls the 5-arg overload, with
        // a null suggestedFix for non-STDIO/no-stderr cases like this one.)
        verify(registryService).markErrorDiagnosis(eq(id), any(), any(), any(), any());
        verify(registryService, never()).markConnected(any());
    }

    // --- MCP-DOCTOR readiness gate (usageStatus) --------------------------------
}
