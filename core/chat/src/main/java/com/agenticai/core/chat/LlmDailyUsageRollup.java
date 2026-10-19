package com.agenticai.core.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One pre-aggregated day of {@code llm_call_log} for a single
 * (day, provider, model, agent, modality) grain - see V119's migration
 * comment for the full rationale (§8 scale: reports serve from this rollup,
 * never a raw scan of {@code llm_call_log} at volume).
 *
 * <p>Written idempotently by {@link LlmUsageRollupService#rollupDay} against
 * the grain unique index: a second run for the same day updates the existing
 * row's aggregates in place rather than inserting a duplicate, so backfills
 * and retries are safe. Schema-per-tenant, same placement as {@link
 * LlmCallLog}.
 */
@Entity
@Table(name = "llm_daily_usage_rollup")
public class LlmDailyUsageRollup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "provider_name", nullable = false, length = 32)
    private String providerName;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "modality", nullable = false, length = 16)
    private String modality;

    @Column(name = "total_calls", nullable = false)
    private long totalCalls;

    @Column(name = "error_count", nullable = false)
    private long errorCount;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "estimated_cost_usd", nullable = false, precision = 16, scale = 6)
    private BigDecimal estimatedCostUsd;

    @Column(name = "latency_sum_ms", nullable = false)
    private long latencySumMs;

    @Column(name = "latency_p50_ms")
    private Double latencyP50Ms;

    @Column(name = "latency_p95_ms")
    private Double latencyP95Ms;

    @Column(name = "distinct_sessions", nullable = false)
    private long distinctSessions;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LlmDailyUsageRollup() {
        // JPA
    }

    public LlmDailyUsageRollup(
        LocalDate usageDate,
        String providerName,
        String model,
        UUID agentId,
        String modality
    ) {
        this.usageDate = usageDate;
        this.providerName = providerName;
        this.model = model;
        this.agentId = agentId;
        this.modality = modality;
        this.estimatedCostUsd = BigDecimal.ZERO;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Overwrites the aggregate columns with a freshly recomputed day (the
     * rollup job always recomputes a whole day from scratch rather than
     * incrementing, so a mid-day re-run self-heals) and stamps
     * {@code updated_at}. The grain columns are never touched.
     */
    public void applyAggregates(
        long totalCalls,
        long errorCount,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        BigDecimal estimatedCostUsd,
        long latencySumMs,
        Double latencyP50Ms,
        Double latencyP95Ms,
        long distinctSessions
    ) {
        this.totalCalls = totalCalls;
        this.errorCount = errorCount;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.estimatedCostUsd = estimatedCostUsd == null ? BigDecimal.ZERO : estimatedCostUsd;
        this.latencySumMs = latencySumMs;
        this.latencyP50Ms = latencyP50Ms;
        this.latencyP95Ms = latencyP95Ms;
        this.distinctSessions = distinctSessions;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getModel() {
        return model;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getModality() {
        return modality;
    }

    public long getTotalCalls() {
        return totalCalls;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public BigDecimal getEstimatedCostUsd() {
        return estimatedCostUsd;
    }

    public long getLatencySumMs() {
        return latencySumMs;
    }

    public Double getLatencyP50Ms() {
        return latencyP50Ms;
    }

    public Double getLatencyP95Ms() {
        return latencyP95Ms;
    }

    public long getDistinctSessions() {
        return distinctSessions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
