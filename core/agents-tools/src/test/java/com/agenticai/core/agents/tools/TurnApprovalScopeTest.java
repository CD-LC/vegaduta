package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.agenticai.core.guardrails.TurnApprovalState;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The turn's approval state reaches the tool guards through a thread-scoped hand-off opened by
 * {@code AgentToolProvider#resolveToolsFor(TurnContext, ...)}. That hand-off must never outlive the
 * resolution: a pooled thread that kept it would give the NEXT turn resolved on it the previous
 * turn's state (parked flag and memory id included).
 */
class TurnApprovalScopeTest {

    private static final UUID AGENT = UUID.randomUUID();

    private static AgentService agents() {
        AgentService agentService = mock(AgentService.class);
        when(agentService.getAutonomyTier(any())).thenReturn(AgentAutonomyTier.SEMI_AUTONOMOUS);
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of());
        when(agentService.getReasoningDepth(any())).thenReturn(ReasoningDepth.FAST);
        return agentService;
    }

    private static ToolCallback rawTool() {
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("lookup")
                                .description("Looks something up")
                                .inputSchema("{\"type\":\"object\"}")
                                .build());
        return tool;
    }

    private static TurnContext contextWith(TurnApprovalState state) {
        return new TurnContext(
                        null, AGENT, "sess-1", "req-1", Instant.now(), null, null, state);
    }

    @Test
    void theHandOffIsRemovedAfterResolutionSoAPooledThreadNeverLeaksItToTheNextTurn() {
        AgentService agentService = agents();
        AgentToolProvider provider =
                ToolHarnessBridge.provider(
                        agentService,
                        new PatternBasedGuardrailService(),
                        mock(ApprovalPolicyService.class),
                        mock(HumanApprovalGateService.class),
                        mock(ToolCallLogRepository.class),
                        15,
                        List.of(rawTool()));
        TurnApprovalState state = new TurnApprovalState("c1:scoped");

        List<ToolCallback> tools =
                provider.resolveToolsFor(
                        contextWith(state),
                        AGENT,
                        "sess-1",
                        "subject",
                        "look it up",
                        null,
                        null,
                        ReasoningDepth.FAST);

        assertTrue(!tools.isEmpty(), "the harness tool is offered");
        assertNull(AgentToolGuard.TURN_APPROVAL_SCOPE.get(), "the hand-off is closed again");
    }

    @Test
    void theHandOffIsRemovedWhenResolutionFails() {
        AgentService agentService = agents();
        when(agentService.getAutonomyTier(any())).thenThrow(new IllegalStateException("db down"));
        AgentToolProvider provider =
                ToolHarnessBridge.provider(
                        agentService,
                        new PatternBasedGuardrailService(),
                        mock(ApprovalPolicyService.class),
                        mock(HumanApprovalGateService.class),
                        mock(ToolCallLogRepository.class),
                        15,
                        List.of(rawTool()));

        assertThrows(
                RuntimeException.class,
                () ->
                        provider.resolveToolsFor(
                                contextWith(new TurnApprovalState("c1:scoped")),
                                AGENT,
                                "sess-1",
                                "subject",
                                "look it up",
                                null,
                                null,
                                ReasoningDepth.FAST));

        assertNull(AgentToolGuard.TURN_APPROVAL_SCOPE.get());
    }
}
