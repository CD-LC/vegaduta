package com.agenticai.core.agents.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class RunBudgetTest {

    // $1 / $5 per M tokens, 10_000 avg tokens/branch => 8000 in + 2000 out = $0.018 per branch.
    private static RunBudget budget(String cap) {
        return new RunBudget(new BigDecimal(cap), BigDecimal.ONE, new BigDecimal("5"), 10_000);
    }

    @Test
    void estimateScalesWithBranches() {
        assertEquals(0, new BigDecimal("0.054").compareTo(budget("2").estimateUsd(3)));
    }

    @Test
    void zeroCapDisablesBudget() {
        RunBudget b = budget("0");
        assertFalse(b.isEnabled());
        assertFalse(b.isExceeded());
        assertEquals(8, b.affordableBranches(8));
    }

    @Test
    void recordAccumulatesAndExceeds() {
        RunBudget b = budget("0.001");
        assertFalse(b.isExceeded());
        b.record("x".repeat(4000), "y".repeat(4000)); // 1000 in + 1000 out = $0.006
        assertTrue(b.isExceeded());
        assertTrue(b.stopMessage().contains("Run budget reached"));
        assertEquals(0, b.affordableBranches(3));
    }

    @Test
    void recordDelegateFloorsAtOneAverageBranch() {
        RunBudget b = budget("2");
        b.recordDelegate("q", "a"); // tiny text, but a delegate ran a whole loop: floor = $0.018
        assertEquals(0, new BigDecimal("0.018").compareTo(b.spentUsd()));
        b.recordDelegate(
                "x".repeat(40_000), "y".repeat(40_000)); // 10k in + 10k out = $0.06 > floor
        assertEquals(0, new BigDecimal("0.078").compareTo(b.spentUsd()));
    }

    @Test
    void disabledBudgetKeepsLegacyDelegateParallelBehaviour() throws Exception {
        AgentChatService chat = mock(AgentChatService.class);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(chat.delegateCall(eq(a), any(), any(), any())).thenReturn("A");
        when(chat.delegateCall(eq(b), any(), any(), any())).thenReturn("B");
        DelegateToTeamToolCallback team =
                new DelegateToTeamToolCallback(
                        chat,
                        List.of(
                                new DelegateToTeamToolCallback.Member(
                                        a, "Researcher", "researcher"),
                                new DelegateToTeamToolCallback.Member(b, "Writer", "writer")),
                        null,
                        budget("0"));

        JsonNode root =
                com.agenticai.core.json.JsonMappers.plain()
                        .readTree(
                                team.call(
                                        "{\"tasks\":["
                                                + "{\"agent\":\"researcher\",\"question\":\"q1\"},"
                                                + "{\"agent\":\"writer\",\"question\":\"q2\"}]}"));

        assertFalse(root.has("costEstimate"));
        assertFalse(root.has("stopReason"));
        assertEquals("ok", root.get("results").get(0).get("status").asString());
        assertEquals("ok", root.get("results").get(1).get("status").asString());
    }

    @Test
    void affordableBranchesTrimsToRemainingBudget() {
        assertEquals(2, budget("0.04").affordableBranches(5)); // 0.04 / 0.018 = 2
    }

    @Test
    void delegateParallelReportsEstimateAndTrimsBranches() throws Exception {
        AgentChatService chat = mock(AgentChatService.class);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(chat.delegateCall(eq(a), any(), any(), any())).thenReturn("A");
        when(chat.delegateCall(eq(b), any(), any(), any())).thenReturn("B");
        DelegateToTeamToolCallback team =
                new DelegateToTeamToolCallback(
                        chat,
                        List.of(
                                new DelegateToTeamToolCallback.Member(
                                        a, "Researcher", "researcher"),
                                new DelegateToTeamToolCallback.Member(b, "Writer", "writer")),
                        null,
                        budget("0.02"));

        JsonNode root =
                com.agenticai.core.json.JsonMappers.plain()
                        .readTree(
                                team.call(
                                        "{\"tasks\":["
                                                + "{\"agent\":\"researcher\",\"question\":\"q1\"},"
                                                + "{\"agent\":\"writer\",\"question\":\"q2\"}]}"));

        assertTrue(root.get("costEstimate").asString().contains("estimated cost"));
        assertEquals(RunBudget.STOP_REASON, root.get("stopReason").asString());
        assertEquals("ok", root.get("results").get(0).get("status").asString());
        assertEquals("skipped", root.get("results").get(1).get("status").asString());
    }
}
