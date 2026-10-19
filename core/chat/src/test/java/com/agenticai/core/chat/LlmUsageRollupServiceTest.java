package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.chat.LlmUsageRollupService.Grain;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure aggregation/formatting core of {@link LlmUsageRollupService}
 * - {@code fold} (the source-agnostic grain reducer every breakdown and the
 * summary go through) and the CSV helpers - without a Spring context or a
 * database, matching {@code LlmPricingCatalogTest}'s posture. The DB-bound
 * paths ({@code rollupDay}, the live-today fallback) are thin wrappers over
 * repository queries and are exercised at integration level.
 */
class LlmUsageRollupServiceTest {

    private static final LocalDate DAY = LocalDate.parse("2026-07-20");

    private static Grain grain(
        String provider, String model, UUID agentId, long calls, long errors,
        long promptTok, long complTok, String cost, long latencySum, Double p50, Double p95, long sessions
    ) {
        return new Grain(
            DAY, provider, model, agentId, ModalityClassifier.classify(model),
            calls, errors, promptTok, complTok, promptTok + complTok,
            new BigDecimal(cost), latencySum, p50, p95, sessions
        );
    }

    @Test
    void foldSumsCountsTokensAndCost() {
        var m = LlmUsageRollupService.fold(List.of(
            grain("GEMINI", "gemini-2.0-flash", UUID.randomUUID(), 10, 1, 1000, 500, "0.10", 5000, 100.0, 300.0, 4),
            grain("GEMINI", "gemini-2.0-flash", UUID.randomUUID(), 30, 2, 3000, 1500, "0.20", 30000, 200.0, 600.0, 6)
        ));

        assertEquals(40, m.totalCalls());
        assertEquals(3, m.errorCount());
        assertEquals(40, m.messageVolume());
        assertEquals(4000, m.promptTokens());
        assertEquals(2000, m.completionTokens());
        assertEquals(6000, m.totalTokens());
        assertEquals(10, m.distinctSessions());
        assertEquals(new BigDecimal("0.30"), m.estimatedCostUsd());
        // (40-3)/40 = 0.925
        assertEquals(0.925, m.successRate(), 1e-9);
        // mean latency = (5000+30000)/40 = 875
        assertEquals(875.0, m.avgLatencyMs(), 1e-9);
        // call-weighted p50 = (100*10 + 200*30) / 40 = 175
        assertEquals(175.0, m.latencyP50Ms(), 1e-9);
        // call-weighted p95 = (300*10 + 600*30) / 40 = 525
        assertEquals(525.0, m.latencyP95Ms(), 1e-9);
        // cache hit-rate is not tracked at call-log level
        assertNull(m.cacheHitRate());
    }

    @Test
    void foldEmptyIsAllZeroNoFabricatedLatency() {
        var m = LlmUsageRollupService.fold(List.of());
        assertEquals(0, m.totalCalls());
        assertEquals(0.0, m.successRate(), 1e-9);
        assertNull(m.avgLatencyMs());
        assertNull(m.latencyP50Ms());
        assertNull(m.latencyP95Ms());
        assertEquals(BigDecimal.ZERO, m.estimatedCostUsd());
    }

    @Test
    void foldIgnoresNullPercentilesInWeighting() {
        var m = LlmUsageRollupService.fold(List.of(
            grain("GROK", "llama-3.1-8b", null, 5, 0, 100, 100, "0.01", 500, null, null, 2),
            grain("GROK", "llama-3.1-8b", null, 5, 0, 100, 100, "0.01", 1500, 200.0, 400.0, 2)
        ));
        // only the second grain contributes a percentile -> weighted == that grain's value
        assertEquals(200.0, m.latencyP50Ms(), 1e-9);
        assertEquals(400.0, m.latencyP95Ms(), 1e-9);
        // mean latency still spans both: (500+1500)/10 = 200
        assertEquals(200.0, m.avgLatencyMs(), 1e-9);
    }

    @Test
    void metricsCsvRendersEmptyForNullLatency() {
        var m = LlmUsageRollupService.fold(List.of());
        String row = LlmUsageRollupService.metricsCsv(m);
        // trailing avg,p50,p95 all empty for a no-data bucket
        assertTrue(row.endsWith(",,,"), "expected empty latency fields, got: " + row);
    }

    @Test
    void csvEscapesCommasAndQuotes() {
        assertEquals("plain", LlmUsageRollupService.csv("plain"));
        assertEquals("\"a,b\"", LlmUsageRollupService.csv("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", LlmUsageRollupService.csv("say \"hi\""));
        assertEquals("", LlmUsageRollupService.csv(null));
    }

    @Test
    void sec046CsvNeutralisesFormulaCells() {
        assertEquals("'=1+1", LlmUsageRollupService.csv("=1+1"));
        assertEquals("'+cmd|' /C calc'!A0", LlmUsageRollupService.csv("+cmd|' /C calc'!A0"));
        assertEquals("'-1+cmd", LlmUsageRollupService.csv("-1+cmd"));
        assertEquals("'@SUM(A1)", LlmUsageRollupService.csv("@SUM(A1)"));
        assertEquals("\"'=HYPERLINK(\"\"x\"\",\"\"y\"\")\"", LlmUsageRollupService.csv("=HYPERLINK(\"x\",\"y\")"));
        assertEquals("\"'\r=1\"", LlmUsageRollupService.csv("\r=1"));
        assertEquals("-5", LlmUsageRollupService.csv("-5"));
        assertEquals("gpt-4o", LlmUsageRollupService.csv("gpt-4o"));
    }
}
