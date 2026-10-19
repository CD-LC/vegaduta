package com.agenticai.core.chat;

import java.math.BigDecimal;
import java.util.List;

/**
 * Real aggregates over the existing {@link LlmCallLog} table, built for the
 * AXON Dashboard's telemetry strip - direct feedback: "is this real data?
 * ... expectation is to really plug the live data." Every field here is
 * computed from calls this tenant's schema has already logged; nothing is
 * invented or jittered. See {@link LlmCallLogRepository}'s native queries
 * for the exact SQL.
 *
 * <p>{@code p50LatencyMs}/{@code p95LatencyMs}/{@code p99LatencyMs} and
 * {@code modelSplit} are computed over the last 24 hours (a small,
 * low-traffic tenant would otherwise see a percentile computed from 2 or 3
 * calls, which reads as noise, not a real distribution). {@code
 * tokensPerMinuteRecent} deliberately uses a much tighter 5-minute window -
 * it's meant to answer "what's happening right now," not "what happened
 * today." Any field is {@code null} (never a fabricated 0 or placeholder)
 * when there's no data in its window - a quiet tenant sees "no recent calls"
 * in the UI, not a fake-looking zero.
 */
public record LlmDashboardMetrics(
    long callsLast24h,
    Double tokensPerMinuteRecent,
    Long p50LatencyMs,
    Long p95LatencyMs,
    Long p99LatencyMs,
    BigDecimal costTodayUsd,
    BigDecimal costPerConversationTodayUsd,
    long conversationsToday,
    List<ModelShare> modelSplitLast24h
) {
    public record ModelShare(String model, long calls, double percent) {
    }
}
