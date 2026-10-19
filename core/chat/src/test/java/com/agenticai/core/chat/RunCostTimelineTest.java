package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RunCostTimelineTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static LlmCallLog row(
        int offsetSeconds, long latencyMs, boolean success, Long prompt, Long completion, String cost,
        Long cacheWrite, Long cacheRead
    ) {
        return new LlmCallLog(
            AGENT, "s1", ProviderName.ANTHROPIC, "claude-sonnet-5", prompt, completion,
            prompt == null || completion == null ? null : prompt + completion,
            cost == null ? null : new BigDecimal(cost), success, null, null, null, null, latencyMs,
            T0.plusSeconds(offsetSeconds), null, "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
            cacheWrite, cacheRead, null);
    }

    @Test
    void sumsCostTokensAndOrdersStepsByStartWithCumulativeCost() {
        // given out of order
        RunCostTimeline.Summary summary = RunCostTimeline.of("s1", AGENT, List.of(
            row(10, 2000, true, 100L, 50L, "0.002", null, null),
            row(0, 1000, true, 200L, 80L, "0.001", null, null),
            row(20, 500, false, null, null, null, null, null)));

        assertEquals(3, summary.callCount());
        assertEquals(1, summary.failedCalls());
        assertEquals(1, summary.unpricedCalls(), "a null-cost call is unpriced, not free");
        assertEquals(0, new BigDecimal("0.003").compareTo(summary.totalCostUsd()));
        assertEquals(300L, summary.promptTokens());
        assertEquals(130L, summary.completionTokens());
        assertEquals(List.of(0L, 10_000L, 20_000L), summary.steps().stream().map(RunCostTimeline.Step::offsetMs).toList());
        assertEquals(0, new BigDecimal("0.001").compareTo(summary.steps().get(0).cumulativeCostUsd()));
        assertEquals(0, new BigDecimal("0.003").compareTo(summary.steps().get(2).cumulativeCostUsd()));
        assertEquals(20_500L, summary.wallClockMs(), "first start to last end");
        assertEquals(3500L, summary.summedLatencyMs());
        assertEquals("0af7651916cd43dd8448eb211c80319c", summary.steps().get(0).traceId());
    }

    @Test
    void cacheHitRatioUsesAnthropicDisjointBucketsAndIsNullWhenUnreported() {
        RunCostTimeline.Summary summary = RunCostTimeline.of("s1", AGENT, List.of(
            // 200 fresh + 1800 read = 2000 total -> 0.9
            row(0, 100, true, 200L, 10L, "0.001", 0L, 1800L),
            // no cache dimension at all -> unknown, excluded from the run ratio
            row(1, 100, true, 5000L, 10L, "0.001", null, null),
            // total miss measured: 1000 fresh, 0 read, 1000 written -> 0.0 (loud, not null)
            row(2, 100, true, 1000L, 10L, "0.001", 1000L, 0L)));

        assertEquals(0.9d, summary.steps().get(0).cacheHitRatio(), 1e-9);
        assertNull(summary.steps().get(1).cacheHitRatio());
        assertEquals(0.0d, summary.steps().get(2).cacheHitRatio(), 1e-9);
        assertEquals(2, summary.cacheAccountedCalls());
        // run ratio: 1800 / (2000 + 2000), the unreported call excluded from both sides
        assertEquals(0.45d, summary.cacheHitRatio(), 1e-9);
        assertEquals(1800L, summary.cacheReadTokens());
        assertEquals(1000L, summary.cacheWriteTokens());
    }

    @Test
    void noCacheDataAnywhereYieldsNullRatioNotZero() {
        RunCostTimeline.Summary summary = RunCostTimeline.of("s1", AGENT, List.of(
            row(0, 100, true, 100L, 10L, "0.001", null, null)));
        assertNull(summary.cacheHitRatio());
        assertEquals(0, summary.cacheAccountedCalls());
    }

    @Test
    void emptyRunIsAnEmptySummary() {
        RunCostTimeline.Summary summary = RunCostTimeline.of("s1", AGENT, List.of());
        assertEquals(0, summary.callCount());
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.totalCostUsd()));
        assertNull(summary.startedAt());
        assertTrue(summary.steps().isEmpty());
    }

    @Test
    void reasoningTokensAreRecoveredFromTotalsOnlyWhenOutsideCompletionAndNeverBilledTwice() {
        // Gemini shape: total 2000 = prompt 1000 + completion 200 + thoughts 800
        LlmCallLog gemini = new LlmCallLog(
            AGENT, "s1", ProviderName.GEMINI, "gemini-2.5-pro", 1000L, 200L, 2000L,
            new BigDecimal("0.0110"), true, null, null, null, null, 100, T0, null, null, null, null, null, null);
        // Anthropic/OpenAI shape: total == prompt + completion -> unknown, not 0
        LlmCallLog folded = row(1, 100, true, 100L, 50L, "0.001", null, null);

        RunCostTimeline.Summary summary = RunCostTimeline.of("s1", AGENT, List.of(gemini, folded));

        assertEquals(800L, summary.steps().get(0).reasoningTokens());
        assertNull(summary.steps().get(1).reasoningTokens());
        assertEquals(800L, summary.reasoningTokens());
        assertEquals(0, new BigDecimal("0.0120").compareTo(summary.totalCostUsd()), "cost is the stored sum, untouched");
    }

    @Test
    void runWithNoReasoningDataHasNullReasoningTotal() {
        assertNull(RunCostTimeline.of("s1", AGENT, List.of(row(0, 100, true, 100L, 10L, "0.001", null, null))).reasoningTokens());
    }

    // ------------------------------------------------------------ controller

    private AgentSessionLlmUsageController controller(TenantPlan plan, List<LlmCallLog> rows) {
        LlmCallLogService service = mock(LlmCallLogService.class);
        when(service.forTask("s1")).thenReturn(rows);
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.plan("t1")).thenReturn(plan);
        TenantContext.set(TenantContext.of("t1", "u1", Set.of()));
        return new AgentSessionLlmUsageController(service, limits);
    }

    @Test
    void summaryEndpointScopesToTheAgentInThePath() {
        LlmCallLog other = new LlmCallLog(
            UUID.randomUUID(), "s1", ProviderName.OPENAI, "gpt-x", 1L, 1L, 2L, new BigDecimal("9"), true, null, null,
            null, null, 1, T0, null, null, null, null, null, null);
        AgentSessionLlmUsageController controller = controller(
            TenantPlan.PREMIUM, List.of(row(0, 100, true, 100L, 10L, "0.001", null, null), other));

        RunCostTimeline.Summary summary = controller.summary(AGENT, "s1");

        assertEquals(1, summary.callCount(), "another agent's call in the same session must not leak in");
        assertEquals(0, new BigDecimal("0.001").compareTo(summary.totalCostUsd()));
    }

    @Test
    void summaryEndpointKeepsThePremiumGate() {
        AgentSessionLlmUsageController controller = controller(TenantPlan.FREE, List.of());
        assertThrows(TenantLimitExceededException.class, () -> controller.summary(AGENT, "s1"));
    }
}
