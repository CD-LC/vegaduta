package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.devices.DeviceCommand;
import com.agenticai.core.devices.DeviceException;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/**
 * Characterisation of {@link WorkflowNodeExecutor}: agent, supervisor, tool and device-control
 * nodes.
 */
@SuppressWarnings("unchecked")
class WorkflowNodeExecutorAgentNodesCharacterizationTest
        extends WorkflowNodeExecutorCharacterizationTestBase {

    // ---- agent -------------------------------------------------------------------------

    @Test
    void agentNodeRepliesAndLogsTransparencyHeader() {
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                turn ->
                                        java.util.Objects.equals(turn.agentId(), AGENT)
                                                && java.util.Objects.equals(turn.sessionId(), RUN)
                                                && java.util.Objects.equals(turn.subjectKey(), RUN)
                                                && java.util.Objects.equals(
                                                        turn.userMessage(), "hi")
                                                && java.util.Objects.equals(turn.turnRef(), "n1"))))
                .thenReturn(Flux.just("he", "llo"));
        when(toolLogs.findByAgentIdAndSessionIdOrderByStartedAtAsc(AGENT, RUN))
                .thenReturn(List.of());
        NodeExecutionResult r = run(node("agent", Map.of("agentId", AGENT.toString())), "hi");
        assertEquals("OK", r.status());
        assertEquals("hello", r.output());
        assertEquals("hello", r.summary());
        assertTrue(
                r.logLines().stream()
                        .anyMatch(
                                l ->
                                        l.startsWith(
                                                "Calling agent "
                                                        + AGENT
                                                        + " with input (2 chars): hi")));
        assertTrue(r.logLines().contains("Agent replied (5 chars) - no tools called."));
    }

    @Test
    void agentNodeServesStepCacheHitWithoutCallingTheAgent() {
        when(cache.get(any(), eq(AGENT), anyString(), eq("hi")))
                .thenReturn(java.util.Optional.of("cached"));
        WorkflowIntelligenceSettings settings =
                new WorkflowIntelligenceSettings(true, false, null, null);
        NodeExecutionResult r =
                executor.executeNode(
                        WF.toString(),
                        "wf",
                        RUN,
                        node("agent", Map.of("agentId", AGENT.toString())),
                        "hi",
                        "tester",
                        settings);
        assertEquals("cached", r.output());
        assertTrue(r.logLines().contains("Cache hit - returning cached response for this input."));
        verify(chat, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void agentNodeWritesStepCacheOnMiss() {
        when(cache.get(any(), eq(AGENT), anyString(), eq("hi")))
                .thenReturn(java.util.Optional.empty());
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                turn ->
                                        java.util.Objects.equals(turn.agentId(), AGENT)
                                                && java.util.Objects.equals(turn.sessionId(), RUN)
                                                && java.util.Objects.equals(turn.subjectKey(), RUN)
                                                && java.util.Objects.equals(
                                                        turn.userMessage(), "hi")
                                                && java.util.Objects.equals(turn.turnRef(), "n1"))))
                .thenReturn(Flux.just("fresh"));
        WorkflowIntelligenceSettings settings =
                new WorkflowIntelligenceSettings(true, false, null, null);
        NodeExecutionResult r =
                executor.executeNode(
                        WF.toString(),
                        "wf",
                        RUN,
                        node("agent", Map.of("agentId", AGENT.toString())),
                        "hi",
                        "tester",
                        settings);
        assertEquals("fresh", r.output());
        verify(cache).put(any(), eq(AGENT), anyString(), eq("hi"), eq("fresh"));
    }

    // ---- supervisor --------------------------------------------------------------------

    @Test
    void supervisorGuards() {
        assertEquals(
                "No agent selected for this Supervisor node.",
                run(node("supervisor", Map.of()), "x").summary());
        assertEquals(
                "No routes configured for this Supervisor node.",
                run(node("supervisor", Map.of("agentId", AGENT.toString())), "x").summary());
        NodeExecutionResult bad =
                run(
                        node(
                                "supervisor",
                                Map.of(
                                        "agentId",
                                        "nope",
                                        "routes",
                                        List.of(Map.of("handle", "a")))),
                        "x");
        assertEquals("Invalid agent id: nope", bad.summary());
    }

    @Test
    void supervisorRoutesByReplyAndFallsBackToFirstRoute() {
        Map<String, Object> cfg =
                Map.of(
                        "agentId",
                        AGENT.toString(),
                        "routes",
                        List.of(
                                Map.of("handle", "billing", "label", "Billing"),
                                Map.of("handle", "tech", "label", "Tech")));
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                turn ->
                                        java.util.Objects.equals(turn.agentId(), AGENT)
                                                && java.util.Objects.equals(turn.sessionId(), RUN)
                                                && java.util.Objects.equals(turn.subjectKey(), RUN)
                                                && turn.userMessage() != null)))
                .thenReturn(Flux.just("Tech"));
        NodeExecutionResult r = run(node("supervisor", cfg), "my wifi is down");
        assertEquals("tech", r.branch());
        assertEquals("Supervisor routed to 'tech'.", r.summary());

        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                turn ->
                                        java.util.Objects.equals(turn.agentId(), AGENT)
                                                && java.util.Objects.equals(turn.sessionId(), RUN)
                                                && java.util.Objects.equals(turn.subjectKey(), RUN)
                                                && turn.userMessage() != null)))
                .thenReturn(Flux.just("gibberish"));
        assertEquals("billing", run(node("supervisor", cfg), "x").branch());

        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                turn ->
                                        java.util.Objects.equals(turn.agentId(), AGENT)
                                                && java.util.Objects.equals(turn.sessionId(), RUN)
                                                && java.util.Objects.equals(turn.subjectKey(), RUN)
                                                && turn.userMessage() != null)))
                .thenThrow(new RuntimeException("boom"));
        NodeExecutionResult failed = run(node("supervisor", cfg), "x");
        assertEquals("billing", failed.branch());
        assertEquals("Supervisor defaulted to 'billing' (routing call failed).", failed.summary());
    }

    // ---- tool --------------------------------------------------------------------------

    @Test
    void toolGuards() {
        assertEquals(
                "No agent selected for this Tool node.",
                run(node("tool", Map.of()), "x").summary());
        assertEquals(
                "No tool selected for this Tool node.",
                run(node("tool", Map.of("agentId", AGENT.toString())), "x").summary());
        assertEquals(
                "Invalid agent id: zz",
                run(node("tool", Map.of("agentId", "zz", "toolName", "t")), "x").summary());
    }

    private ToolCallback tool(String name, String reply) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);
        when(cb.getToolDefinition()).thenReturn(def);
        when(cb.call(anyString())).thenReturn(reply);
        return cb;
    }

    @Test
    void toolCallsNamedToolAndMapsFailures() {
        Map<String, Object> cfg = Map.of("agentId", AGENT.toString(), "toolName", "lookup");
        ToolCallback lookup = tool("lookup", "found");
        when(toolProvider.resolveToolCandidatesFor(AGENT, RUN, RUN)).thenReturn(List.of(lookup));
        NodeExecutionResult ok = run(node("tool", cfg), "in");
        assertEquals("found", ok.output());
        assertEquals("OK", ok.status());
        assertEquals("Calling tool 'lookup' with input: in", ok.logLines().get(0));

        ToolCallback other = tool("other", "x");
        when(toolProvider.resolveToolCandidatesFor(AGENT, RUN, RUN)).thenReturn(List.of(other));
        NodeExecutionResult missing = run(node("tool", cfg), "in");
        assertEquals("SKIPPED", missing.status());
        assertTrue(
                missing.summary()
                        .startsWith("Tool 'lookup' is not currently available to this agent"));

        when(toolProvider.resolveToolCandidatesFor(AGENT, RUN, RUN))
                .thenThrow(new RuntimeException("down"));
        NodeExecutionResult failed = run(node("tool", cfg), "in");
        assertEquals("SKIPPED", failed.status());
        assertEquals("Tool 'lookup' call failed: down", failed.summary());
    }

    // ---- device control ----------------------------------------------------------------

    @Test
    void deviceControlGuardsAndEnqueue() {
        assertEquals(
                "No device selected for this Device Control node.",
                run(node("device_control", Map.of()), "x").summary());
        UUID device = UUID.randomUUID();
        assertEquals(
                "No command type configured for this Device Control node.",
                run(node("device_control", Map.of("deviceId", device.toString())), "x").summary());
        assertEquals(
                "Invalid device id: bad",
                run(node("device_control", Map.of("deviceId", "bad", "commandType", "ping")), "x")
                        .summary());

        DeviceCommand cmd = mock(DeviceCommand.class);
        UUID cmdId = UUID.randomUUID();
        when(cmd.getId()).thenReturn(cmdId);
        when(devices.enqueue(eq(device), eq("ping"), eq("payload-in"), eq(UUID.fromString(RUN))))
                .thenReturn(cmd);
        NodeExecutionResult ok =
                run(
                        node(
                                "device_control",
                                Map.of("deviceId", device.toString(), "commandType", "ping")),
                        "payload-in");
        assertEquals("OK", ok.status());
        assertEquals("payload-in", ok.output());
        assertEquals(
                "Command queued for device (not executed within this run - see log).",
                ok.summary());
        assertEquals(
                "Queued command " + cmdId + " (ping) for device " + device + ".",
                ok.logLines().get(0));

        when(devices.enqueue(any(), any(), any(), any())).thenThrow(new DeviceException("offline"));
        NodeExecutionResult failed =
                run(
                        node(
                                "device_control",
                                Map.of("deviceId", device.toString(), "commandType", "ping")),
                        "x");
        assertEquals("SKIPPED", failed.status());
        assertEquals("offline", failed.summary());
    }
}
