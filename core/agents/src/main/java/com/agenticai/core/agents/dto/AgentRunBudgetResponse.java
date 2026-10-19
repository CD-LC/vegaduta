package com.agenticai.core.agents.dto;

import java.math.BigDecimal;

/**
 * GET/PATCH /api/agents/{agentId}/run-budget - the per-run spending cap an agent's
 * delegation tools and deep_research run under.
 *
 * @param overrideEnabled {@code app.guardrails.run-budget.per-agent-override-enabled}; false = the
 *     agent value is ignored and PATCH answers 409
 * @param globalCapUsd platform cap ({@code app.guardrails.run-budget.usd}); 0 = no platform cap
 * @param agentCapUsd the value stored on the agent, null when none
 * @param effectiveCapUsd what a turn actually runs under; 0 = no cap
 * @param maxAgentCapUsd the highest value PATCH accepts, null = no upper bound
 * @param perBranchEstimateUsd estimated cost of one delegated sub-agent call
 * @param scope always "delegation tools only" - the cap does not bound a single-agent tool loop
 * @param estimate always true - spend is estimated from text length, it is not billing
 */
public record AgentRunBudgetResponse(
    boolean overrideEnabled,
    BigDecimal globalCapUsd,
    BigDecimal agentCapUsd,
    BigDecimal effectiveCapUsd,
    BigDecimal maxAgentCapUsd,
    BigDecimal perBranchEstimateUsd,
    String scope,
    boolean estimate
) {
}
