package com.agenticai.core.agents.dto;

/**
 * Fallback observability follow-up (#956, "platform-admin alert threshold") -
 * a cross-agent SUM of {@link com.agenticai.core.agents.core.Agent#getFallbackTriggeredCount()}/
 * {@link com.agenticai.core.agents.core.Agent#getFallbackFailedCount()} for the
 * current tenant schema (whichever schema {@code TenantContext} is pointed
 * at when the query runs), produced by {@code AgentRepository#sumFallbackCounts()}
 * via a JPQL constructor expression rather than fetching every agent row just
 * to sum two longs client-side.
 */
public record AgentFallbackTotals(long triggeredTotal, long failedTotal) {
}
