package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.guardrails.PatternBasedGuardrailService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The turn's tool activity reaches the tool wrappers through the same thread-scoped hand-off the
 * approval state uses: a tool resolved for a turn reports to THAT turn's {@code TurnContext}, through
 * the production wrapper chain, and the hand-off is closed again afterwards.
 */
class TurnToolActivityScopeTest {

    private static final UUID AGENT = UUID.randomUUID();

    private static AgentService agents() {
        AgentService agentService = mock(AgentService.class);
        when(agentService.getAutonomyTier(any())).thenReturn(AgentAutonomyTier.SEMI_AUTONOMOUS);
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of());
        when(agentService.getReasoningDepth(any())).thenReturn(ReasoningDepth.FAST);
        return agentService;
    }

    private static ToolCallback rawTool(java.util.concurrent.atomic.AtomicInteger inFlightSeen, TurnContext[] turn) {
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.getToolDefinition())
                .thenReturn(ToolDefinition.builder()
                        .name("lookup")
                        .description("Looks something up")
                        .inputSchema("{\"type\":\"object\"}")
                        .build());
        when(tool.call(any(String.class))).thenAnswer(inv -> {
            inFlightSeen.set(turn[0].toolActivity().inFlightCount());
            return "{\"found\":true}";
        });
        return tool;
    }

    @Test
    void aToolResolvedForATurnReportsToThatTurnsActivityThroughTheProductionChain() {
        TurnContext[] turn = new TurnContext[1];
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger(-1);
        turn[0] = new TurnContext(null, AGENT, "sess-1", "req-1", Instant.now(), null, null);
        AgentToolProvider provider = ToolHarnessBridge.provider(
                agents(),
                new PatternBasedGuardrailService(),
                mock(ApprovalPolicyService.class),
                mock(HumanApprovalGateService.class),
                mock(ToolCallLogRepository.class),
                15,
                List.of(rawTool(inFlight, turn)));

        List<ToolCallback> tools = provider.resolveToolsFor(
                turn[0], AGENT, "sess-1", "subject", "look it up", null, null, ReasoningDepth.FAST);
        assertTrue(!tools.isEmpty());
        assertEquals(0, turn[0].toolActivity().snapshot().finished().size(), "nothing ran at resolution");

        tools.get(0).call("{}");

        assertEquals(1, inFlight.get(), "the tool was in flight while it ran");
        assertEquals(1, turn[0].toolActivity().snapshot().finished().get("lookup").executed());
        assertEquals(0, turn[0].toolActivity().inFlightCount());
    }

    @Test
    void twoTurnsNeverShareOneActivity() {
        TurnContext[] first = new TurnContext[1];
        TurnContext[] second = new TurnContext[1];
        java.util.concurrent.atomic.AtomicInteger ignored = new java.util.concurrent.atomic.AtomicInteger();
        first[0] = new TurnContext(null, AGENT, "sess-1", "req-1", Instant.now(), null, null);
        second[0] = new TurnContext(null, AGENT, "sess-2", "req-2", Instant.now(), null, null);
        AgentToolProvider provider = ToolHarnessBridge.provider(
                agents(),
                new PatternBasedGuardrailService(),
                mock(ApprovalPolicyService.class),
                mock(HumanApprovalGateService.class),
                mock(ToolCallLogRepository.class),
                15,
                List.of(rawTool(ignored, first)));

        List<ToolCallback> firstTools = provider.resolveToolsFor(
                first[0], AGENT, "sess-1", "subject", "look it up", null, null, ReasoningDepth.FAST);
        provider.resolveToolsFor(
                second[0], AGENT, "sess-2", "subject", "look it up", null, null, ReasoningDepth.FAST);
        firstTools.get(0).call("{}");

        assertTrue(first[0].toolActivity().anyToolRan());
        assertTrue(!second[0].toolActivity().anyToolRan());
    }

    @Test
    void theHandOffIsClosedAfterResolution() {
        TurnContext turn = new TurnContext(null, AGENT, "sess-1", "req-1", Instant.now(), null, null);
        AgentToolProvider provider = ToolHarnessBridge.provider(
                agents(),
                new PatternBasedGuardrailService(),
                mock(ApprovalPolicyService.class),
                mock(HumanApprovalGateService.class),
                mock(ToolCallLogRepository.class),
                15,
                List.of(rawTool(new java.util.concurrent.atomic.AtomicInteger(), new TurnContext[] {turn})));

        provider.resolveToolsFor(turn, AGENT, "sess-1", "subject", "look it up", null, null, ReasoningDepth.FAST);

        assertNull(AgentToolGuard.TURN_ACTIVITY_SCOPE.get());
    }
}
