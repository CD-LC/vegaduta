package com.agenticai.core.chat;

/**
 * Cross-provider totals over the durable {@link LlmCallLog} table - what
 * {@code SystemStatusController}'s Dashboard "LLM calls"/"LLM latency"
 * readouts surface. Direct feedback: "after restart always LLM calls 0" -
 * those readouts previously came from {@link LlmCallMetricsService}, an
 * in-process counter that's deliberately reset on every restart (see its own
 * javadoc). That's a reasonable number for "since this process started," but
 * reads as broken to someone who just restarted the app and sent a message
 * five seconds ago - the Dashboard looked wrong even though nothing was
 * actually failing. Since {@link LlmCallLog} already persists every call
 * durably (added for the Platform Maintenance "LLM usage &amp; cost" panel),
 * the Dashboard now reads from the same source of truth instead of a
 * separate number that can drift from - and reset independently of - it.
 *
 * @param avgLatencyMs {@code null} when {@code totalCalls} is 0 (JPQL {@code
 *     AVG} over an empty result set) - the honest "no calls yet" case, never
 *     fabricated as {@code 0.0}.
 */
public record LlmOverallUsageSummary(long totalCalls, long errorCount, Double avgLatencyMs) {
}
