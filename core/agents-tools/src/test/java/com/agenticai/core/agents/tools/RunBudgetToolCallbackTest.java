package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.collab.RunBudget;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;

class RunBudgetToolCallbackTest {

    // $1 / $5 per M tokens, 10_000 avg tokens => $0.018 per delegate call.
    private static RunBudget budget(String cap) {
        return new RunBudget(new BigDecimal(cap), BigDecimal.ONE, new BigDecimal("5"), 10_000);
    }

    private static ToolCallback tool(String result) {
        ToolCallback t = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn("ask_agent_x");
        when(t.getToolDefinition()).thenReturn(def);
        when(t.call("{}")).thenReturn(result);
        return t;
    }

    @Test
    void recordsDelegateSpendThenStopsOnceCapIsHit() throws Exception {
        RunBudget b = budget("0.02");
        ToolCallback raw = tool("answer");
        RunBudgetToolCallback cb = new RunBudgetToolCallback(raw, b, true);

        assertEquals("answer", cb.call("{}")); // $0.018 spent, under the cap
        assertEquals("answer", cb.call("{}")); // $0.036 spent, now over
        String stopped = cb.call("{}");

        JsonNode root = com.agenticai.core.json.JsonMappers.plain().readTree(stopped);
        assertEquals(RunBudget.STOP_REASON, root.get("stopReason").asString());
        assertTrue(root.get("error").asString().contains("Run budget reached"));
        verify(raw, org.mockito.Mockito.times(2)).call("{}");
    }

    @Test
    void doesNotRecordWhenToolChargesItself() {
        RunBudget b = budget("2");
        new RunBudgetToolCallback(tool("answer"), b, false).call("{}");
        assertEquals(0, BigDecimal.ZERO.compareTo(b.spentUsd()));
    }

    @Test
    void stopJsonIsAlwaysValidJson() throws Exception {
        RunBudget b = budget("0.001");
        b.record("x".repeat(4000), "y".repeat(4000));
        JsonNode root =
                com.agenticai.core.json.JsonMappers.plain()
                        .readTree(RunBudgetToolCallback.stopJson(b));
        assertEquals(RunBudget.STOP_REASON, root.get("stopReason").asString());
    }

    @Test
    void exceededBudgetNeverInvokesTheTool() {
        RunBudget b = budget("0.001");
        b.record("x".repeat(4000), "y".repeat(4000));
        ToolCallback raw = tool("answer");
        new RunBudgetToolCallback(raw, b, true).call("{}");
        verify(raw, never()).call("{}");
    }
}
