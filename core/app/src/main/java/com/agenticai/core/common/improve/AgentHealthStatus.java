package com.agenticai.core.common.improve;

/**
 * Coarse bucket of an {@link AgentHealthScore#getScore()} for UI badges,
 * so the console never has to re-derive the thresholds
 * {@link AgentHealthScoreService} already applied.
 */
public enum AgentHealthStatus {
    /** score &gt;= {@link AgentHealthScoreService#HEALTHY_THRESHOLD}. */
    HEALTHY,
    /** score &gt;= {@link AgentHealthScoreService#AT_RISK_THRESHOLD}, below HEALTHY. */
    AT_RISK,
    /** score below {@link AgentHealthScoreService#AT_RISK_THRESHOLD}. */
    DEGRADED
}
