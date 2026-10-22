package com.agenticai.core.agents.tools.mcpregistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.McpConnectionPhase;
import com.agenticai.core.mcp.McpDynamicConnector;
import com.agenticai.core.mcp.dto.McpConnectionTestResult;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class RedisMcpOwnershipGateTest {

    private final UUID reg = UUID.randomUUID();
    private McpOwnershipRegistry registry;
    private McpCommandBus bus;
    private McpDynamicConnector local;

    @BeforeEach
    void setUp() {
        registry = mock(McpOwnershipRegistry.class);
        when(registry.replicaId()).thenReturn("me");
        when(registry.active()).thenReturn(true);
        bus = mock(McpCommandBus.class);
        local = mock(McpDynamicConnector.class);
        // user request thread
        TenantContext.set(TenantContext.of("t1", "user-42", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private RedisMcpOwnershipGate gate(boolean exclusive) {
        RedisMcpOwnershipGate g =
                new RedisMcpOwnershipGate(
                        registry,
                        bus,
                        exclusive,
                        Duration.ofMillis(200),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(5),
                        Runnable::run,
                        Runnable::run);
        g.attach(local, (t, id) -> false);
        return g;
    }

    // ---- connect ----

    @Test
    void ownerConnectsLocallyAndReplicatedModeBroadcastsUserConnectToPeers() {
        when(registry.claim("t1", reg)).thenReturn(true);
        when(registry.liveReplicas()).thenReturn(List.of("me", "peer"));
        assertFalse(gate(false).routeConnect("t1", reg));
        verify(bus).send(eq("peer"), any(McpCommand.class));
        verify(bus, never()).send(eq("me"), any(McpCommand.class));
    }

    @Test
    void systemActorConnectIsNotBroadcast() {
        TenantContext.set(TenantContext.of("t1", "system:mcp-reconnect", Set.of()));
        when(registry.claim("t1", reg)).thenReturn(false);
        assertFalse(gate(false).routeConnect("t1", reg));
        verify(bus, never()).send(anyString(), any());
    }

    @Test
    void exclusiveNonOwnerRoutesConnectToTheOwner() throws Exception {
        when(registry.claim("t1", reg)).thenReturn(false);
        when(registry.ownerOf("t1", reg)).thenReturn(Optional.of("peer"));
        when(bus.sendAndAwait(eq("peer"), eq(McpCommand.Op.CONNECT), eq("t1"), eq(reg), any()))
                .thenReturn(Optional.of("ok"));
        assertTrue(gate(true).routeConnect("t1", reg), "handled remotely, caller must not connect");
    }

    @Test
    void ownerTimeoutWithValidLeaseDoesNotTakeOverAndDoesNotDoubleConnect() throws Exception {
        when(registry.claim("t1", reg)).thenReturn(false);
        when(registry.ownerOf("t1", reg)).thenReturn(Optional.of("peer"));
        when(bus.sendAndAwait(anyString(), any(), anyString(), any(), any()))
                .thenReturn(Optional.empty());
        assertTrue(
                gate(true).routeConnect("t1", reg),
                "lease still valid: no split-brain second client");
    }

    @Test
    void ownerTimeoutAfterLeaseExpiryTakesOverAndConnectsLocally() throws Exception {
        when(registry.claim("t1", reg))
                .thenReturn(false, true); // 1st: held by peer; 2nd: lease lapsed
        when(registry.ownerOf("t1", reg)).thenReturn(Optional.of("peer"));
        when(bus.sendAndAwait(anyString(), any(), anyString(), any(), any()))
                .thenReturn(Optional.empty());
        assertFalse(gate(true).routeConnect("t1", reg));
    }

    @Test
    void redisFailureFailsOpenToLocalConnect() throws Exception {
        when(registry.claim("t1", reg)).thenReturn(false);
        when(registry.ownerOf("t1", reg)).thenReturn(Optional.of("peer"));
        when(bus.sendAndAwait(anyString(), any(), anyString(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));
        assertFalse(gate(true).routeConnect("t1", reg));
    }

    // ---- disconnect / test ----

    @Test
    void disconnectIsBroadcastToEveryOtherLiveReplica() {
        when(registry.liveReplicas()).thenReturn(List.of("me", "p1", "p2"));
        gate(false).broadcastDisconnect("t1", reg);
        verify(bus).send(eq("p1"), any(McpCommand.class));
        verify(bus).send(eq("p2"), any(McpCommand.class));
        verify(bus, never()).send(eq("me"), any(McpCommand.class));
    }

    @Test
    void testIsRoutedToTheOwnerAndItsResultReturned() throws Exception {
        when(registry.ownerOf("t1", reg)).thenReturn(Optional.of("peer"));
        String json =
                new tools.jackson.databind.ObjectMapper()
                        .writeValueAsString(McpConnectionTestResult.success(3));
        when(bus.sendAndAwait(eq("peer"), eq(McpCommand.Op.TEST), eq("t1"), eq(reg), any()))
                .thenReturn(Optional.of(json));
        Optional<McpConnectionTestResult> r = gate(false).routeTest("t1", reg);
        assertTrue(r.orElseThrow().ok());
        assertEquals(3, r.get().toolCount());
    }

    @Test
    void testTimeoutFallsBackToALocalThrowawayTest() throws Exception {
        when(registry.ownerOf("t1", reg)).thenReturn(Optional.of("peer"));
        when(bus.sendAndAwait(anyString(), any(), anyString(), any(), any()))
                .thenReturn(Optional.empty());
        assertTrue(gate(false).routeTest("t1", reg).isEmpty());
    }

    @Test
    void testWithNoOtherOwnerRunsLocally() throws Exception {
        when(registry.ownerOf("t1", reg)).thenReturn(Optional.of("me"));
        assertTrue(gate(false).routeTest("t1", reg).isEmpty());
        verify(bus, never()).sendAndAwait(anyString(), any(), anyString(), any(), any());
    }

    // ---- sweeps ----

    @Test
    void retrySweepOnlyRunsForTheReplicaThatHoldsTheLease() {
        when(registry.claim("t1", reg)).thenReturn(false);
        assertFalse(gate(false).mayRetry("t1", reg));
        when(registry.claim("t1", reg)).thenReturn(true);
        assertTrue(gate(false).mayRetry("t1", reg));
    }

    @Test
    void healthDiagnosisIsPersistedOnlyByTheOwnerOrWhenUnowned() {
        RedisMcpOwnershipGate g = gate(false);
        when(registry.ownerOf("t1", reg)).thenReturn(Optional.of("peer"));
        assertFalse(g.mayPersistHealth("t1", reg));
        when(registry.ownerOf("t1", reg)).thenReturn(Optional.of("me"));
        assertTrue(g.mayPersistHealth("t1", reg));
        when(registry.ownerOf("t1", reg)).thenReturn(Optional.empty());
        assertTrue(g.mayPersistHealth("t1", reg));
    }

    // ---- command handling (off the request thread) ----

    @Test
    void handlerReappliesTheTenantRunsLocallyAndClearsContext() {
        AtomicReference<String> seenTenant = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            seenTenant.set(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse("MISSING"));
                            // a routed command must never be re-routed
                            assertFalse(gate(true).routeConnect("t1", reg));
                            return null;
                        })
                .when(local)
                .connect(reg);
        TenantContext.clear(); // pool thread has no ambient tenant
        gate(true).handle(new McpCommand(McpCommand.Op.CONNECT, "tenantX", reg, "rk"));
        assertEquals("tenantX", seenTenant.get());
        assertTrue(TenantContext.current().isEmpty(), "tenant must not leak onto the pool thread");
        verify(bus).reply(any(McpCommand.class), eq("ok"));
        verify(registry, never()).claim(anyString(), any());
    }

    @Test
    void routedTestReturnsTheSerializedResult() {
        when(local.test(reg))
                .thenReturn(
                        McpConnectionTestResult.failure(
                                McpConnectionPhase.HANDSHAKE, "boom", "hint"));
        gate(false).handle(new McpCommand(McpCommand.Op.TEST, "t1", reg, "rk"));
        verify(bus).reply(any(McpCommand.class), org.mockito.ArgumentMatchers.contains("boom"));
    }

    @Test
    void pollDispatchesDrainedCommandsAndSurvivesRedisErrors() {
        McpCommand c = new McpCommand(McpCommand.Op.DISCONNECT, "t1", reg, null);
        when(bus.drain("me", 20)).thenReturn(List.of(c));
        gate(false).pollCommands();
        verify(local).disconnect(reg);

        when(bus.drain("me", 20)).thenThrow(new RedisConnectionFailureException("down"));
        gate(false).pollCommands(); // must not throw
    }

    @Test
    void heartbeatRenewsOwnedLeasesAndRegistersTheReplica() {
        gate(false).heartbeat();
        verify(registry).registerReplica();
        verify(registry).renewOwned(any(), eq(Duration.ofMinutes(5)));
    }
}
