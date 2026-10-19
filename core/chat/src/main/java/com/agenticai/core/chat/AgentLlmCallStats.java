package com.agenticai.core.chat;

import java.time.Instant;

/**
 * Task #959 ("Agent performance: response time + reliability + failure
 * handling") - per-AGENT aggregate over the durable {@link LlmCallLog} table,
 * the missing counterpart to {@link LlmOverallUsageSummary} (cross-provider,
 * whole-tenant) and {@link LlmDashboardMetrics} (whole-tenant, windowed).
 * Every {@link LlmCallLog} row already carries a nullable {@code agentId} -
 * populated for every agent chat turn and Quick Task run via {@code
 * LlmMetricsAdvisor.AGENT_ID_PARAM} (see {@code AgentChatService#streamFast}
 * et al.) - so this is a straight {@code WHERE agent_id = :agentId}
 * aggregate over data this platform was already recording; nothing new is
 * captured, this just groups what's there by agent instead of by provider or
 * tenant.
 *
 * <p>Deliberately all-time (no rolling window, unlike {@link
 * LlmDashboardMetrics}'s 24h/5min windows): an individual agent's call volume
 * is typically far lower than the whole tenant's, so a short window would
 * frequently read as "no data" for a perfectly healthy, lightly-used agent.
 * {@link #firstCallAt}/{@link #lastCallAt} let a caller see recency for
 * themselves without this record silently dropping older calls.
 *
 * @param avgLatencyMs {@code null} when {@code totalCalls} is 0 (JPQL {@code
 *     AVG} over an empty result set) - the honest "no data yet" case for an
 *     agent that has never actually run, never fabricated as {@code 0.0}.
 */
public record AgentLlmCallStats(
    long totalCalls,
    long failedCalls,
    Double avgLatencyMs,
    Instant firstCallAt,
    Instant lastCallAt
) {
}
