package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of {@link LlmCallLogRepository#summarizeByProvider()} - a plain
 * class (not a record) because it's built via a JPQL constructor
 * expression ({@code new com.agenticai...LlmProviderUsageSummary(...)}),
 * which requires an exact, ordered constructor match but doesn't care
 * whether the target is a record or a class. Kept as a class for that
 * reason, with the same immutable/getter shape a record would give.
 */
public class LlmProviderUsageSummary {

    private final ProviderName providerName;
    private final long totalCalls;
    private final long errorCount;
    private final long totalTokens;
    private final BigDecimal estimatedCostUsd;
    private final Instant firstCallAt;
    private final Instant lastCallAt;

    public LlmProviderUsageSummary(
        ProviderName providerName,
        long totalCalls,
        long errorCount,
        long totalTokens,
        BigDecimal estimatedCostUsd,
        Instant firstCallAt,
        Instant lastCallAt
    ) {
        this.providerName = providerName;
        this.totalCalls = totalCalls;
        this.errorCount = errorCount;
        this.totalTokens = totalTokens;
        this.estimatedCostUsd = estimatedCostUsd;
        this.firstCallAt = firstCallAt;
        this.lastCallAt = lastCallAt;
    }

    public ProviderName getProviderName() {
        return providerName;
    }

    public long getTotalCalls() {
        return totalCalls;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public BigDecimal getEstimatedCostUsd() {
        return estimatedCostUsd;
    }

    public Instant getFirstCallAt() {
        return firstCallAt;
    }

    public Instant getLastCallAt() {
        return lastCallAt;
    }
}
