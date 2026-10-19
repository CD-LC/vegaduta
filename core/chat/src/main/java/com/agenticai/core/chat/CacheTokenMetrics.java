package com.agenticai.core.chat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * Emits the cached-vs-uncached input-token split of a finished call as its own meters, with the
 * same {@code tenant}/{@code provider}/{@code model} tags as {@link MicrometerLlmMetrics} so the
 * cache view joins against {@code llm.cost.usd}:
 *
 * <ul>
 *   <li>{@code llm.cache.accounted.calls} - calls that reported a usable cache split at all: the
 *       honest DENOMINATOR for every ratio (not {@code llm.calls}, which would read unmeasured
 *       providers as cache misses).
 *   <li>{@code llm.input.tokens.cache.read} / {@code .cache.write} / {@code .uncached} - three
 *       disjoint counters partitioning the call's accounted input tokens, so the fleet hit ratio is
 *       {@code rate(read) / (rate(read) + rate(write) + rate(uncached))}.
 *   <li>{@code llm.cache.hit.ratio} - a per-call distribution ("what fraction of individual calls
 *       are hitting"), which the aggregate counter ratio cannot answer.
 * </ul>
 *
 * <p><b>Absent dimensions create no series at all.</b> {@code meterRegistry.counter(...)} REGISTERS
 * a meter as a side effect of lookup, so a null-guarded zero would publish a permanent {@code 0}
 * series indistinguishable from a measured total miss. Each counter is touched only when its own
 * count is non-null, and nothing at all when {@link CacheTokenAccounting#isPresent()} is false.
 */
final class CacheTokenMetrics implements LlmCallSink {

    private final MeterRegistry meterRegistry;

    CacheTokenMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void onCall(LlmCallObservation obs) {
        CacheTokenAccounting cacheAccounting = obs.tokens().cache();
        if (cacheAccounting == null || !cacheAccounting.isPresent()) {
            return;
        }
        Tags tags = MicrometerLlmMetrics.tags(obs.providerName(), obs.model());
        meterRegistry.counter("llm.cache.accounted.calls", tags).increment();
        if (cacheAccounting.cacheReadInputTokens() != null) {
            meterRegistry
                    .counter("llm.input.tokens.cache.read", tags)
                    .increment(cacheAccounting.cacheReadInputTokens().doubleValue());
        }
        if (cacheAccounting.cacheWriteInputTokens() != null) {
            meterRegistry
                    .counter("llm.input.tokens.cache.write", tags)
                    .increment(cacheAccounting.cacheWriteInputTokens().doubleValue());
        }
        if (cacheAccounting.uncachedInputTokens() != null) {
            meterRegistry
                    .counter("llm.input.tokens.uncached", tags)
                    .increment(cacheAccounting.uncachedInputTokens().doubleValue());
        }
        Double hitRatio = cacheAccounting.hitRatio();
        if (hitRatio != null) {
            meterRegistry.summary("llm.cache.hit.ratio", tags).record(hitRatio.doubleValue());
        }
    }
}
