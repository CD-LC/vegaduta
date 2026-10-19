package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Per-run cost, cache-hit-ratio and step-timeline view, derived purely from the
 * {@code llm_call_log} rows a run (= one chat/task session id) already wrote -
 * no new persistence. This is the backend for a future "run cost + timeline"
 * UI; nothing renders it yet.
 *
 * <p><b>Honest limits.</b>
 * <ul>
 *   <li>Steps are LLM calls. Tool executions are not persisted anywhere
 *       queryable (they exist only as {@code execute_tool} spans and live step
 *       events), so they are not in this timeline; the {@code traceId} on each
 *       step is the join key to the tool spans in the trace backend.</li>
 *   <li>Cache columns are only populated for providers whose billable cache
 *       counts are disjoint from prompt tokens (Anthropic - see {@link
 *       CacheTokenAccounting#billableCacheReadTokens()}). For every other
 *       provider the per-step and run ratios are {@code null} ("unknown"),
 *       never {@code 0.0}.</li>
 *   <li>{@code reasoningTokens} is only the thinking that a provider reports outside
 *       completion tokens (Gemini); see {@link #outOfCompletionReasoningTokens}. It is
 *       informational and already inside the cost - never add it to the cost again.</li>
 *   <li>Cost is the stored {@code estimated_cost_usd}: it includes Gemini
 *       thinking tokens for rows written after the reasoning-pricing change;
 *       older rows are unchanged. Unpriced calls (null cost) are counted in
 *       {@code unpricedCalls} instead of being treated as free.</li>
 * </ul>
 */
public final class RunCostTimeline {

    private RunCostTimeline() {
    }

    public record Step(
        int index,
        UUID id,
        long offsetMs,
        long latencyMs,
        ProviderName providerName,
        String model,
        boolean success,
        Long promptTokens,
        Long completionTokens,
        BigDecimal costUsd,
        BigDecimal cumulativeCostUsd,
        Long cacheReadTokens,
        Long cacheWriteTokens,
        Double cacheHitRatio,
        UUID agentVersionId,
        String traceId,
        String spanId,
        Instant startedAt,
        Long reasoningTokens
    ) {
    }

    public record Summary(
        String sessionId,
        UUID agentId,
        int callCount,
        int failedCalls,
        int unpricedCalls,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        BigDecimal totalCostUsd,
        long cacheReadTokens,
        long cacheWriteTokens,
        int cacheAccountedCalls,
        Double cacheHitRatio,
        Instant startedAt,
        Instant endedAt,
        long wallClockMs,
        long summedLatencyMs,
        List<Step> steps,
        Long reasoningTokens
    ) {
    }

    /** Builds the summary from one run's rows in any order; empty input yields an empty summary. */
    public static Summary of(String sessionId, UUID agentId, List<LlmCallLog> rows) {
        List<LlmCallLog> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(LlmCallLog::getStartedAt));

        List<Step> steps = new ArrayList<>(ordered.size());
        BigDecimal total = BigDecimal.ZERO;
        long prompt = 0;
        long completion = 0;
        long totalTokens = 0;
        long cacheRead = 0;
        long cacheWrite = 0;
        long summedLatency = 0;
        int failed = 0;
        int unpriced = 0;
        int cacheAccounted = 0;
        long reasoning = 0;
        boolean anyReasoning = false;
        // Denominator of the run-level ratio: input tokens of the cache-accounted calls only.
        long cacheAccountedInput = 0;
        Instant start = ordered.isEmpty() ? null : ordered.get(0).getStartedAt();
        Instant end = start;

        for (int i = 0; i < ordered.size(); i++) {
            LlmCallLog row = ordered.get(i);
            if (!row.isSuccess()) {
                failed++;
            }
            if (row.getEstimatedCostUsd() == null) {
                unpriced++;
            } else {
                total = total.add(row.getEstimatedCostUsd());
            }
            prompt += nz(row.getPromptTokens());
            completion += nz(row.getCompletionTokens());
            totalTokens += nz(row.getTotalTokens());
            summedLatency += row.getLatencyMs();
            Instant rowEnd = row.getStartedAt().plusMillis(row.getLatencyMs());
            if (rowEnd.isAfter(end)) {
                end = rowEnd;
            }
            Long stepReasoning = outOfCompletionReasoningTokens(row);
            if (stepReasoning != null) {
                reasoning += stepReasoning;
                anyReasoning = true;
            }
            Double ratio = hitRatio(row);
            if (ratio != null) {
                cacheAccounted++;
                cacheRead += nz(row.getCacheReadTokens());
                cacheWrite += nz(row.getCacheCreationTokens());
                cacheAccountedInput += nz(row.getPromptTokens()) + nz(row.getCacheReadTokens())
                    + nz(row.getCacheCreationTokens());
            }
            steps.add(new Step(
                i, row.getId(), Duration.between(start, row.getStartedAt()).toMillis(), row.getLatencyMs(),
                row.getProviderName(), row.getModel(), row.isSuccess(),
                row.getPromptTokens(), row.getCompletionTokens(), row.getEstimatedCostUsd(), total,
                row.getCacheReadTokens(), row.getCacheCreationTokens(), ratio,
                row.getAgentVersionId(), row.getTraceId(), row.getSpanId(), row.getStartedAt(), stepReasoning));
        }

        Double runRatio = cacheAccountedInput > 0 ? (double) cacheRead / (double) cacheAccountedInput : null;
        long wallClock = start == null ? 0 : Duration.between(start, end).toMillis();
        return new Summary(
            sessionId, agentId, ordered.size(), failed, unpriced, prompt, completion, totalTokens, total,
            cacheRead, cacheWrite, cacheAccounted, runRatio, start, start == null ? null : end, wallClock,
            summedLatency, List.copyOf(steps), anyReasoning ? reasoning : null);
    }

    /**
     * cache_read / (prompt + cache_read + cache_write) for one call, {@code null}
     * when the row has no cache dimension (unknown, not zero) or a zero total.
     */
    static Double hitRatio(LlmCallLog row) {
        if (row.getCacheReadTokens() == null) {
            return null;
        }
        long total = nz(row.getPromptTokens()) + nz(row.getCacheReadTokens()) + nz(row.getCacheCreationTokens());
        return total <= 0 ? null : (double) row.getCacheReadTokens() / (double) total;
    }

    /**
     * Thinking tokens the provider billed OUTSIDE {@code completion_tokens}, recovered from the
     * row's own totals ({@code total - prompt - completion}) because no column stores them and no
     * migration may add one. Today that is Gemini ({@code thoughtsTokenCount} is its own bucket,
     * priced at the output rate and already inside {@code estimated_cost_usd} for rows written
     * since the reasoning-pricing change). {@code null} (unknown, never 0) when the provider
     * folds reasoning into completion tokens - OpenAI reasoning, Anthropic extended thinking -
     * where the tokens are already in {@code completionTokens} and the cost; those cannot be split
     * out of a stored row.
     */
    static Long outOfCompletionReasoningTokens(LlmCallLog row) {
        if (row.getPromptTokens() == null || row.getCompletionTokens() == null || row.getTotalTokens() == null) {
            return null;
        }
        long extra = row.getTotalTokens() - row.getPromptTokens() - row.getCompletionTokens();
        return extra > 0 ? extra : null;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
