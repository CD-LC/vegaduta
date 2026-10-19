package com.agenticai.core.chat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase P4 (Spend &amp; Reports) response shapes -
 * PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §3 ("Spend by tenant / user /
 * model / modality / day; token &amp; message volume, cache hit-rate,
 * latency p50/p95"). All reports are scoped to the CURRENT tenant's own
 * {@code llm_call_log}/rollup (the "by tenant" axis in the doc is the scope
 * itself here, resolved from {@code TenantContext}); the cross-tenant
 * platform-admin roll-up is {@code PlatformObservabilityService}'s job, not
 * this tenant-admin surface.
 *
 * <p>A container of records rather than one file each - they are a single
 * cohesive DTO family with no behavior, only shape.
 */
public final class SpendReport {

    private SpendReport() {
    }

    /**
     * The metric block every breakdown row and the summary share. {@code
     * cacheHitRate} is always {@code null} today: {@code llm_call_log} has no
     * cache-hit flag, so an honest "not measured here" rather than a
     * fabricated rate - the doc lists it as "if available."
     *
     * <p>{@code latencyP50Ms}/{@code latencyP95Ms} are exact when a row maps
     * to a single stored grain and a call-weighted approximation when several
     * grains are merged (see {@link LlmUsageRollupService}); {@code
     * avgLatencyMs} is always exact (sum of latency / calls). {@code
     * distinctSessions} summed across grains is an upper bound.
     */
    public record UsageMetrics(
        long totalCalls,
        long errorCount,
        double successRate,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long messageVolume,
        long distinctSessions,
        BigDecimal estimatedCostUsd,
        Double avgLatencyMs,
        Double latencyP50Ms,
        Double latencyP95Ms,
        Double cacheHitRate
    ) {
    }

    public record Summary(
        LocalDate fromDate,
        LocalDate toDate,
        boolean includesLiveToday,
        UsageMetrics metrics
    ) {
    }

    public record DayUsage(LocalDate date, UsageMetrics metrics) {
    }

    public record ModelUsage(String model, String modality, UsageMetrics metrics) {
    }

    public record ProviderUsage(String providerName, UsageMetrics metrics) {
    }

    public record AgentUsage(UUID agentId, UsageMetrics metrics) {
    }

    public record ModalityUsage(String modality, UsageMetrics metrics) {
    }

    /** The grouping axes the CSV export and the breakdown endpoints share. */
    public enum GroupBy {
        DAY,
        MODEL,
        PROVIDER,
        AGENT,
        MODALITY
    }
}
