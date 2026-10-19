package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.mcpregistry.McpOwnershipGate;
import com.agenticai.core.mcp.McpCatalogService;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.mcp.McpServerRegistration;
import com.agenticai.core.mcp.McpStdioCommandGuard;
import com.agenticai.core.mcp.atlassian.AtlassianOAuthService;
import com.agenticai.core.mcp.dto.McpConnectionTestResult;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Wiring of {@link McpOwnershipGate} into the manager (replica-safe registry,
 * app.mcp.registry-store=redis).
 */
class DynamicMcpConnectionManagerOwnershipTest {

    private final McpRegistryService registryService = mock(McpRegistryService.class);
    private final TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
    private final McpOwnershipGate gate = mock(McpOwnershipGate.class);
    private final UUID reg = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path cacheBase;

    @SuppressWarnings("unchecked")
    private DynamicMcpConnectionManager manager() {
        ObjectProvider<AgentToolProvider> none = mock(ObjectProvider.class);
        ObjectProvider<com.agenticai.core.agents.attestation.AgentAttestationService> noAtt =
                mock(ObjectProvider.class);
        DynamicMcpConnectionManager m =
                new DynamicMcpConnectionManager(
                        registryService,
                        iterator,
                        new McpRemoteUrlGuard(""),
                        new McpStdioCommandGuard(""),
                        mock(McpStdioSelfHealer.class),
                        new McpCatalogService(),
                        5,
                        new McpStdioSidecarProperties(),
                        none,
                        mock(AtlassianOAuthService.class),
                        new SimpleMeterRegistry(),
                        noAtt,
                        cacheBase.toString());
        m.setOwnershipGate(gate);
        return m;
    }

    private void runIteratorAs(String schema) {
        Mockito.doAnswer(
                        inv -> {
                            TenantContext.set(TenantContext.of(schema, "system:test", Set.of()));
                            try {
                                inv.<Consumer<String>>getArgument(0).accept(schema);
                            } finally {
                                TenantContext.clear();
                            }
                            return null;
                        })
                .when(iterator)
                .forEachActiveTenantSchema(any());
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, McpSyncClient> liveFor(DynamicMcpConnectionManager m, String tenant)
            throws Exception {
        Field f = DynamicMcpConnectionManager.class.getDeclaredField("liveClients");
        f.setAccessible(true);
        Map<UUID, McpSyncClient> tenantClients = new ConcurrentHashMap<>();
        ((Map<String, Map<UUID, McpSyncClient>>) f.get(m)).put(tenant, tenantClients);
        return tenantClients;
    }

    @Test
    void setterAttachesTheManagerAndItsLiveClientView() throws Exception {
        DynamicMcpConnectionManager m = manager();
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.function.BiPredicate<String, UUID>> live =
                org.mockito.ArgumentCaptor.forClass(java.util.function.BiPredicate.class);
        verify(gate).attach(org.mockito.ArgumentMatchers.same(m), live.capture());
        liveFor(m, "t1").put(reg, mock(McpSyncClient.class));
        org.junit.jupiter.api.Assertions.assertTrue(live.getValue().test("t1", reg));
        org.junit.jupiter.api.Assertions.assertFalse(live.getValue().test("t2", reg));
    }

    @Test
    void connectHandedToTheOwnerNeverTouchesTheLocalRegistryPath() {
        TenantContext.set(TenantContext.of("t1", "user", Set.of()));
        when(gate.routeConnect("t1", reg)).thenReturn(true);
        manager().connect(reg);
        verifyNoInteractions(registryService);
    }

    @Test
    void disconnectBroadcastsBeforeTearingDownLocally() {
        TenantContext.set(TenantContext.of("t1", "user", Set.of()));
        manager().disconnect(reg);
        inOrder(gate).verify(gate).broadcastDisconnect("t1", reg);
    }

    @Test
    void routedTestResultIsReturnedWithoutALocalTest() {
        TenantContext.set(TenantContext.of("t1", "user", Set.of()));
        McpConnectionTestResult remote = McpConnectionTestResult.success(7);
        when(gate.routeTest("t1", reg)).thenReturn(Optional.of(remote));
        assertSame(remote, manager().test(reg));
        verifyNoInteractions(registryService);
    }

    @Test
    void retrySweepSkipsRegistrationsThisReplicaDoesNotOwn() {
        runIteratorAs("t1");
        McpServerRegistration r = mock(McpServerRegistration.class);
        when(r.getId()).thenReturn(reg);
        when(registryService.registrationsEligibleForRetry(any())).thenReturn(List.of(r));
        when(gate.mayRetry("t1", reg)).thenReturn(false);
        manager().retryErroredConnections();
        verify(gate).mayRetry("t1", reg);
        verify(gate, after(400).never()).routeConnect(anyString(), any());
    }

    @Test
    void retrySweepRetriesRegistrationsThisReplicaOwns() {
        runIteratorAs("t1");
        McpServerRegistration r = mock(McpServerRegistration.class);
        when(r.getId()).thenReturn(reg);
        when(registryService.registrationsEligibleForRetry(any())).thenReturn(List.of(r));
        when(gate.mayRetry("t1", reg)).thenReturn(true);
        when(gate.routeConnect("t1", reg))
                .thenReturn(true); // stop after the gate: we only assert it was reached
        manager().retryErroredConnections();
        verify(gate, timeout(2000)).routeConnect("t1", reg);
    }

    @Test
    void healthSweepStillDropsADeadLocalClientButOnlyTheOwnerPersistsTheDiagnosis()
            throws Exception {
        runIteratorAs("t1");
        DynamicMcpConnectionManager m = manager();
        McpSyncClient dead = mock(McpSyncClient.class);
        when(dead.listTools()).thenThrow(new RuntimeException("died"));
        Map<UUID, McpSyncClient> live = liveFor(m, "t1");
        live.put(reg, dead);
        when(gate.mayPersistHealth("t1", reg)).thenReturn(false);

        m.healthCheckLiveConnections();

        verify(dead).closeGracefully();
        org.junit.jupiter.api.Assertions.assertTrue(live.isEmpty());
        verify(registryService, never()).markErrorDiagnosis(any(), anyString(), any(), any());
        verify(gate, never())
                .broadcastDisconnect(anyString(), any()); // a death is local, never fanned out
    }
}
