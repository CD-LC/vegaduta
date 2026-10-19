package com.agenticai.core.agents.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task #959 ("Agent performance: response time + reliability + failure
 * handling") - the per-AGENT reliability view a tenant-admin or agent-owner
 * would actually look at, distinct from every existing telemetry surface in
 * this codebase: {@code LlmMetricsAdvisor}/{@code LlmCallLogService} answer
 * "how is this LLM call/provider doing," the Issues portal answers "what
 * failed across the whole platform," and #954-956's fallback counters answer
 * "how often did this agent's SAFETY NET fire." None of those answer "for
 * Agent X specifically, what's its average response time, success rate, and
 * recent failure history" - this record is that missing answer, built
 * entirely from data those existing systems already capture (see {@link
 * AgentReliabilityService}'s javadoc for exactly which tables).
 *
 * <p>Every numeric/timestamp field is honestly nullable/zero rather than
 * fabricated: an agent that has never actually run reports {@code
 * totalCalls: 0}, {@code successRatePercent: null}, {@code
 * avgResponseTimeMs: null}, {@code firstCallAt/lastCallAt: null}, and an
 * empty {@code recentFailures} - the frontend renders that combination as an
 * explicit "no data yet" state, never a misleading 0%/0ms.
 *
 * @param successRatePercent {@code null} when {@code totalCalls} is 0 - "no
 *     data yet," never a fabricated 100% or 0%.
 * @param avgResponseTimeMs {@code null} when {@code totalCalls} is 0 - see
 *     {@code AgentLlmCallStats#avgLatencyMs}'s javadoc for the same rule at
 *     the query layer this is sourced from.
 * @param toolSuccessRatePercent {@code null} when {@code toolCallCount} is
 *     0 - this agent may have no tools attached at all, which is a distinct,
 *     equally honest "no data" case from "tools attached but never failed."
 * @param recentFailures newest first, capped to a small handful (see {@code
 *     LlmCallLogRepository#findTop5ByAgentIdAndSuccessOrderByStartedAtDesc}) -
 *     a glance-able recent-failure list, not a full audit trail.
 */
public record AgentReliabilitySummary(
    UUID agentId,
    long totalCalls,
    long failedCalls,
    Double successRatePercent,
    Long avgResponseTimeMs,
    Instant firstCallAt,
    Instant lastCallAt,
    long toolCallCount,
    long toolCallFailureCount,
    Double toolSuccessRatePercent,
    // #954-956 fallback observability - reused here (not recomputed) straight
    // off the same Agent columns AgentResponse already exposes, so this one
    // reliability panel doesn't require two separate API calls to show both
    // "how often does this agent need its LLM fallback" and "how is it doing
    // overall."
    long fallbackTriggeredCount,
    long fallbackFailedCount,
    Instant lastFallbackAt,
    List<RecentFailure> recentFailures
) {
    /**
     * One recent failed LLM call for this agent, straight off {@code
     * LlmCallLog} - {@code errorMessage}/{@code exceptionType} are exactly
     * what {@code LlmMetricsAdvisor} already captured at the moment of
     * failure (provider exception message/class, or a guardrail/tool-step
     * exception), not a re-derived or summarized value.
     */
    public record RecentFailure(
        Instant occurredAt,
        String errorMessage,
        String exceptionType,
        String provider,
        String model
    ) {
    }
}
