package com.agenticai.core.agents.tools.mcpregistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.McpDynamicConnector;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Routed MCP tool calls (app.mcp.registry-route-tool-calls): off by default, owner-executed, never
 * retried locally.
 */
class RedisMcpOwnershipGateToolCallTest {

    private final UUID reg = UUID.randomUUID();
    private McpOwnershipRegistry registry;
    private McpCommandBus bus;

    @BeforeEach
    void setUp() {
        registry = mock(McpOwnershipRegistry.class);
        when(registry.replicaId()).thenReturn("me");
        bus = mock(McpCommandBus.class);
    }

    private RedisMcpOwnershipGate gate(boolean routeCalls, boolean liveLocally) {
        RedisMcpOwnershipGate g =
                new RedisMcpOwnershipGate(
                        registry,
                        bus,
                        true,
                        Duration.ofMillis(200),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(5),
                        Runnable::run,
                        Runnable::run);
        g.attach(mock(McpDynamicConnector.class), (t, id) -> liveLocally);
        g.configureToolCallRouting(routeCalls, Duration.ofSeconds(2));
        return g;
    }

    @Test
    void flagOffNeverRoutesAndNeverTouchesRedis() {
        assertTrue(gate(false, false).routeToolCall("t1", reg, "mcp__s__echo", "{}").isEmpty());
        verify(registry, never()).ownerOf(anyString(), any());
    }

    @Test
    void localClientWinsOverRouting() {
        assertTrue(gate(true, true).routeToolCall("t1", reg, "mcp__s__echo", "{}").isEmpty());
        verify(registry, never()).ownerOf(anyString(), any());
    }

    @Test
    void noOwnerOrSelfOwnerRunsLocally() {
        when(registry.ownerOf("t1", reg)).thenReturn(Optional.empty());
        assertTrue(gate(true, false).routeToolCall("t1", reg, "x", "{}").isEmpty());
        when(registry.ownerOf("t1", reg)).thenReturn(Optional.of("me"));
        assertTrue(gate(true, false).routeToolCall("t1", reg, "x", "{}").isEmpty());
    }

    @Test
    void routesToTheOwnerAndReturnsItsResult() throws Exception {
        when(registry.ownerOf("t1", reg)).thenReturn(Optional.of("peer"));
        when(bus.sendAndAwait(
                        eq("peer"), eq(McpCommand.Op.CALL), eq("t1"), eq(reg), anyString(), any()))
                .thenReturn(Optional.of("{\"ok\":true,\"result\":\"pong\"}"));
        assertEquals(
                Optional.of("pong"),
                gate(true, false).routeToolCall("t1", reg, "mcp__s__ping", "{\"a\":1}"));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(bus)
                .sendAndAwait(
                        eq("peer"),
                        eq(McpCommand.Op.CALL),
                        eq("t1"),
                        eq(reg),
                        payload.capture(),
                        any());
        assertEquals(
                "mcp__s__ping",
                new ObjectMapper().readTree(payload.getValue()).get("tool").asText());
    }

    @Test
    void silentOwnerOrOwnerErrorThrowsInsteadOfRunningLocally() throws Exception {
        when(registry.ownerOf("t1", reg)).thenReturn(Optional.of("peer"));
        when(bus.sendAndAwait(anyString(), any(), anyString(), any(), anyString(), any()))
                .thenReturn(Optional.empty());
        assertThrows(
                IllegalStateException.class,
                () -> gate(true, false).routeToolCall("t1", reg, "x", "{}"));
        when(bus.sendAndAwait(anyString(), any(), anyString(), any(), anyString(), any()))
                .thenReturn(Optional.of("{\"ok\":false,\"error\":\"boom\"}"));
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> gate(true, false).routeToolCall("t1", reg, "x", "{}"));
        assertTrue(e.getMessage().contains("boom"));
    }

    @Test
    void ownerExecutesCallAndRepliesWithTenantApplied() {
        AtomicReference<String> seen = new AtomicReference<>();
        RedisMcpOwnershipGate g = gate(true, true);
        g.attachToolCaller(
                (tenant, id, tool, args) -> {
                    seen.set(tenant + "|" + tool + "|" + args);
                    return "done";
                });
        McpCommand c =
                new McpCommand(
                        McpCommand.Op.CALL,
                        "t9",
                        reg,
                        "rk",
                        "{\"tool\":\"mcp__s__t\",\"args\":\"{}\"}");
        g.handle(c);
        assertEquals("t9|mcp__s__t|{}", seen.get());
        verify(bus).reply(any(McpCommand.class), eq("{\"result\":\"done\",\"ok\":true}"));
    }

    @Test
    void ownerRepliesWithErrorWhenCallerFailsAndStaysSilentWhenFlagOff() {
        RedisMcpOwnershipGate on = gate(true, true);
        on.attachToolCaller(
                (t, id, tool, args) -> {
                    throw new IllegalStateException("no client");
                });
        on.handle(
                new McpCommand(
                        McpCommand.Op.CALL, "t9", reg, "rk", "{\"tool\":\"x\",\"args\":\"\"}"));
        verify(bus)
                .reply(any(McpCommand.class), org.mockito.ArgumentMatchers.contains("no client"));

        McpCommandBus bus2 = mock(McpCommandBus.class);
        RedisMcpOwnershipGate off =
                new RedisMcpOwnershipGate(
                        registry,
                        bus2,
                        true,
                        Duration.ofMillis(200),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(5),
                        Runnable::run,
                        Runnable::run);
        off.attach(mock(McpDynamicConnector.class), (t, id) -> true);
        off.attachToolCaller((t, id, tool, args) -> "should not run");
        off.handle(
                new McpCommand(
                        McpCommand.Op.CALL, "t9", reg, "rk", "{\"tool\":\"x\",\"args\":\"\"}"));
        verify(bus2, never()).reply(any(), anyString());
    }

    @Test
    void commandJsonRoundTripsAndOldPayloadlessEntriesStillParse() throws Exception {
        ObjectMapper m = new ObjectMapper();
        McpCommand c = new McpCommand(McpCommand.Op.CALL, "t", reg, "rk", "{\"tool\":\"x\"}");
        assertEquals(c, m.readValue(m.writeValueAsString(c), McpCommand.class));
        String legacy =
                "{\"op\":\"CONNECT\",\"tenant\":\"t\",\"registrationId\":\""
                        + reg
                        + "\",\"replyKey\":null}";
        assertEquals(
                new McpCommand(McpCommand.Op.CONNECT, "t", reg, null),
                m.readValue(legacy, McpCommand.class));
    }
}
