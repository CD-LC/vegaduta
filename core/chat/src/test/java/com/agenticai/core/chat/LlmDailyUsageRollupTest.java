package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * One (day, provider, model, agent, modality) grain of the {@code llm_daily_usage_rollup}
 * table: the idempotent recompute path {@link #applyAggregates} - aggregates overwrite in place,
 * the grain columns never move, and a null cost reads as an honest zero.
 */
class LlmDailyUsageRollupTest {

    private static LlmDailyUsageRollup row() {
        return new LlmDailyUsageRollup(
            LocalDate.of(2026, 10, 4), "OPENAI", "gpt-4o", UUID.randomUUID(), "TEXT");
    }

    @Test
    void aFreshRowStartsZeroedWithItsGrainAndTimestamps() {
        LlmDailyUsageRollup r = row();

        assertEquals(LocalDate.of(2026, 10, 4), r.getUsageDate());
        assertEquals("OPENAI", r.getProviderName());
        assertEquals("gpt-4o", r.getModel());
        assertEquals("TEXT", r.getModality());
        assertEquals(0, r.getTotalCalls());
        assertEquals(0, r.getErrorCount());
        assertEquals(BigDecimal.ZERO, r.getEstimatedCostUsd());
        assertEquals(r.getCreatedAt(), r.getUpdatedAt());
        assertNull(r.getLatencyP50Ms());
        assertNull(r.getLatencyP95Ms());
    }

    @Test
    void applyAggregatesOverwritesTheDayInPlace() {
        LlmDailyUsageRollup r = row();
        UUID agent = r.getAgentId();

        r.applyAggregates(120, 3, 60_000, 30_000, 90_000,
            new BigDecimal("1.234567"), 45_000, 380.0, 910.5, 17);

        assertEquals(120, r.getTotalCalls());
        assertEquals(3, r.getErrorCount());
        assertEquals(60_000, r.getPromptTokens());
        assertEquals(30_000, r.getCompletionTokens());
        assertEquals(90_000, r.getTotalTokens());
        assertEquals(new BigDecimal("1.234567"), r.getEstimatedCostUsd());
        assertEquals(45_000, r.getLatencySumMs());
        assertEquals(380.0, r.getLatencyP50Ms());
        assertEquals(910.5, r.getLatencyP95Ms());
        assertEquals(17, r.getDistinctSessions());
        // The grain is never touched by a recompute.
        assertEquals(agent, r.getAgentId());
        assertEquals("OPENAI", r.getProviderName());
    }

    @Test
    void aNullCostIsRecordedAsZeroNotNull() {
        LlmDailyUsageRollup r = row();

        r.applyAggregates(1, 0, 1, 1, 2, null, 100, null, null, 1);

        assertEquals(BigDecimal.ZERO, r.getEstimatedCostUsd());
    }

    @Test
    void aSecondApplyIsTheRerunSelfHealNotAnIncrement() {
        LlmDailyUsageRollup r = row();
        r.applyAggregates(10, 1, 100, 50, 150, BigDecimal.ONE, 500, 10.0, 20.0, 2);
        r.applyAggregates(12, 2, 120, 60, 180, new BigDecimal("2"), 600, 11.0, 21.0, 3);

        assertEquals(12, r.getTotalCalls(), "recompute REPLACES the day, it does not add to it");
        assertEquals(2, r.getErrorCount());
        assertEquals(3, r.getDistinctSessions());
        assertTrue(!r.getUpdatedAt().isBefore(r.getCreatedAt()));
    }
}
