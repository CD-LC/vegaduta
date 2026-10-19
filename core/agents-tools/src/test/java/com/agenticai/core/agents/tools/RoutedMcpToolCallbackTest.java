package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.mcpregistry.McpOwnershipGate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

/**
 * The sender-side hook: pass-through unless the gate routes; a routed failure is never retried
 * locally.
 */
class RoutedMcpToolCallbackTest {

    private final UUID reg = UUID.randomUUID();

    private ToolCallback delegate() {
        ToolCallback d = mock(ToolCallback.class);
        when(d.getToolDefinition()).thenReturn(new DefaultToolDefinition("mcp__s__t", "d", "{}"));
        when(d.call("{}")).thenReturn("local");
        return d;
    }

    @Test
    void runsLocallyWhenGateDoesNotRoute() {
        McpOwnershipGate gate = mock(McpOwnershipGate.class);
        when(gate.routeToolCall("t1", reg, "mcp__s__t", "{}")).thenReturn(Optional.empty());
        assertEquals(
                "local", new RoutedMcpToolCallback(delegate(), "t1", reg, () -> gate).call("{}"));
    }

    @Test
    void usesOwnerResultWhenRouted() {
        ToolCallback d = delegate();
        McpOwnershipGate gate = mock(McpOwnershipGate.class);
        when(gate.routeToolCall("t1", reg, "mcp__s__t", "{}")).thenReturn(Optional.of("remote"));
        assertEquals("remote", new RoutedMcpToolCallback(d, "t1", reg, () -> gate).call("{}"));
        verify(d, never()).call(anyString());
    }

    @Test
    void routedFailureIsNotRetriedLocally() {
        ToolCallback d = delegate();
        McpOwnershipGate gate = mock(McpOwnershipGate.class);
        when(gate.routeToolCall("t1", reg, "mcp__s__t", "{}"))
                .thenThrow(new IllegalStateException("owner down"));
        assertThrows(
                IllegalStateException.class,
                () -> new RoutedMcpToolCallback(d, "t1", reg, () -> gate).call("{}"));
        verify(d, never()).call(anyString());
    }

    @Test
    void definitionIsUnchanged() {
        assertEquals(
                "mcp__s__t",
                new RoutedMcpToolCallback(delegate(), "t1", reg, () -> McpOwnershipGate.NOOP)
                        .getToolDefinition()
                        .name());
    }
}
