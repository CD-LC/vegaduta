package com.agenticai.core.agents.core;

import com.agenticai.core.providers.ProviderName;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import java.util.Objects;

/**
 * An agent's provider-fallback configuration and its observability counters. Embedded in {@link
 * Agent}; the columns keep their names. The counters are mutated only through {@link
 * AgentRepository}'s atomic bulk UPDATEs, never through this value object.
 */
@Embeddable
public class FallbackPolicy {

    // #815 Gap 4 - "no graceful-degradation/model-fallback routing." Both
    // nullable: no fallback configured is the default for every existing
    // agent (migration V73 adds these as nullable, no backfill needed) and
    // for any new agent until a tenant explicitly opts in - see
    // AgentChatService#streamFast's javadoc for why this only ever fires
    // before any tokens have streamed (mid-stream provider switch would
    // garble output, not fix it).
    @Enumerated(EnumType.STRING)
    @Column(name = "fallback_provider_name", length = 32)
    private ProviderName fallbackProviderName;

    @Column(name = "fallback_model", length = 128)
    private String fallbackModel;

    // Fallback observability follow-up (#955) - "per-agent fallback counter
    // on LLM telemetry." Mutated exclusively via AgentRepository's atomic
    // bulk-UPDATE increment methods (never read-modify-write through this
    // entity), so concurrent chat turns hitting the same agent's fallback at
    // once can never lose an increment to a stale in-memory read. Migration
    // V83 adds all three as 0/0/null - no pre-existing agent's fallback has
    // ever fired as far as these columns know, which is simply true.
    @Column(name = "fallback_triggered_count", nullable = false)
    private long fallbackTriggeredCount;

    @Column(name = "fallback_failed_count", nullable = false)
    private long fallbackFailedCount;

    @Column(name = "last_fallback_at")
    private Instant lastFallbackAt;

    /** JPA, and the "no fallback configured" starting state. */
    protected FallbackPolicy() {}

    public static FallbackPolicy none() {
        return new FallbackPolicy();
    }

    /** A null provider clears the fallback (and its model). The counters are carried over. */
    public FallbackPolicy withFallback(ProviderName providerName, String model) {
        FallbackPolicy p = new FallbackPolicy();
        p.fallbackProviderName = providerName;
        p.fallbackModel = providerName == null ? null : model;
        p.fallbackTriggeredCount = fallbackTriggeredCount;
        p.fallbackFailedCount = fallbackFailedCount;
        p.lastFallbackAt = lastFallbackAt;
        return p;
    }

    public ProviderName getProviderName() {
        return fallbackProviderName;
    }

    public String getModel() {
        return fallbackModel;
    }

    public long getTriggeredCount() {
        return fallbackTriggeredCount;
    }

    public long getFailedCount() {
        return fallbackFailedCount;
    }

    public Instant getLastFallbackAt() {
        return lastFallbackAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FallbackPolicy f
                && fallbackProviderName == f.fallbackProviderName
                && Objects.equals(fallbackModel, f.fallbackModel)
                && fallbackTriggeredCount == f.fallbackTriggeredCount
                && fallbackFailedCount == f.fallbackFailedCount
                && Objects.equals(lastFallbackAt, f.lastFallbackAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fallbackProviderName, fallbackModel, fallbackTriggeredCount, fallbackFailedCount, lastFallbackAt);
    }
}
