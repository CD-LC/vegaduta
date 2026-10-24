package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * What a tool call gets once the turn has used up {@code app.guardrails.max-tool-calls-per-turn}.
 *
 * <p>Before: it threw {@link ToolStepLimitExceededException}, which an outer wrapper then turned into
 * "This tool call failed: ... Explain this to the user ... suggest what to try instead" for every later
 * call (and, once that wrapper let the exception through, into a dead turn). Now: the first calls over
 * the ceiling are not run and are answered with the {@code tool_budget_exhausted} result, the same
 * structured result the tool loop's round cap uses; only a model that ignores it is stopped.
 */
class GuardedToolCallbackStepLimitTest {

    private static final String TOOL_INPUT = "{\"q\":\"x\"}";

    private ToolCallback tool;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("lookup");
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(TOOL_INPUT)).thenReturn("found");
        meters = new SimpleMeterRegistry();
    }

    private GuardedToolCallback guarded(int ceiling, ToolStepLimitProperties limits) {
        GuardrailService guardrails = mock(GuardrailService.class);
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
        GuardedToolCallback guard = new GuardedToolCallback(
            tool, new AtomicInteger(0), ceiling, guardrails, mock(ApprovalPolicyService.class),
            mock(HumanApprovalGateService.class), UUID.randomUUID(), "s1", null, false, null, null,
            ApprovalGateMode.POLICY_DEFAULT, null, null, null, null, meters);
        return limits == null ? guard : guard.withStepLimits(limits);
    }

    @Test
    void callsWithinTheCeilingRunNormally() {
        GuardedToolCallback guard = guarded(2, null);

        assertEquals("found", guard.call(TOOL_INPUT));
        assertEquals("found", guard.call(TOOL_INPUT));
        verify(tool, times(2)).call(TOOL_INPUT);
    }

    @Test
    void theFirstCallOverTheCeilingIsNotRunAndTheModelGetsTheBudgetExhaustedResult() throws Exception {
        GuardedToolCallback guard = guarded(1, null);
        guard.call(TOOL_INPUT);

        String result = guard.call(TOOL_INPUT);

        verify(tool, times(1)).call(TOOL_INPUT);
        assertTrue(ToolBudgetExhausted.isBudgetExhausted(result), result);
        JsonNode json = JsonMappers.plain().readTree(result);
        assertTrue(json.isObject(), "Gemini replays every tool result as a JSON object");
        assertEquals(ToolBudgetExhausted.MARKER, json.path("error").asText());
        assertTrue(json.path("result").asText().contains("Do not call any more tools"), result);
        assertTrue(json.path("result").asText().contains("1 tool call"), "names the ceiling: " + result);
        assertFalse(result.contains("This tool call failed"), "not the generic failure text: " + result);
        assertEquals(1, meters.timer(GuardedToolCallback.CALL_TIMER,
            "tool", "lookup", "outcome", "step_limit").count());
    }

    @Test
    void everyCallInsideTheGraceGetsTheSameResultAndNoneRuns() {
        ToolStepLimitProperties limits = new ToolStepLimitProperties();
        limits.setGraceCalls(3);
        GuardedToolCallback guard = guarded(1, limits);
        guard.call(TOOL_INPUT);

        for (int i = 0; i < 3; i++) {
            assertTrue(ToolBudgetExhausted.isBudgetExhausted(guard.call(TOOL_INPUT)), "call " + (i + 2));
        }

        verify(tool, times(1)).call(TOOL_INPUT);
    }

    @Test
    void aModelThatKeepsCallingPastTheGraceIsStoppedWithTheStepLimitError() {
        ToolStepLimitProperties limits = new ToolStepLimitProperties();
        limits.setGraceCalls(2);
        GuardedToolCallback guard = guarded(1, limits);
        guard.call(TOOL_INPUT);
        guard.call(TOOL_INPUT);
        guard.call(TOOL_INPUT);

        assertThrows(ToolStepLimitExceededException.class, () -> guard.call(TOOL_INPUT));
        verify(tool, times(1)).call(TOOL_INPUT);
    }

    @Test
    void theMessageIsConfigurationAndKeepsTheStructuredShape() throws Exception {
        ToolStepLimitProperties limits = new ToolStepLimitProperties();
        limits.setExhaustedMessage("Stop. Ceiling {limit}.");
        GuardedToolCallback guard = guarded(1, limits);
        guard.call(TOOL_INPUT);

        JsonNode json = JsonMappers.plain().readTree(guard.call(TOOL_INPUT));

        assertEquals(ToolBudgetExhausted.MARKER, json.path("error").asText());
        assertEquals("Stop. Ceiling 1.", json.path("result").asText());
    }

    @Test
    void theAutonomyTiersScaleTheCeilingByConfiguration() {
        ToolStepLimitProperties limits = new ToolStepLimitProperties();

        assertEquals(7, limits.supervisedCeiling(15));
        assertEquals(3, limits.supervisedCeiling(4), "never below the minimum");
        assertEquals(30, limits.autonomousCeiling(15));

        limits.setSupervisedDivisor(3);
        limits.setSupervisedMinSteps(5);
        limits.setAutonomousMultiplier(4);

        assertEquals(5, limits.supervisedCeiling(15));
        assertEquals(5, limits.supervisedCeiling(6));
        assertEquals(60, limits.autonomousCeiling(15));
    }

    @Test
    void stagesFollowTheCeilingAndTheGrace() {
        ToolStepLimitProperties limits = new ToolStepLimitProperties();
        limits.setGraceCalls(2);

        assertEquals(ToolStepLimitProperties.Stage.WITHIN, limits.stageOf(5, 5));
        assertEquals(ToolStepLimitProperties.Stage.EXHAUSTED, limits.stageOf(6, 5));
        assertEquals(ToolStepLimitProperties.Stage.EXHAUSTED, limits.stageOf(7, 5));
        assertEquals(ToolStepLimitProperties.Stage.ABORT, limits.stageOf(8, 5));
    }
}
