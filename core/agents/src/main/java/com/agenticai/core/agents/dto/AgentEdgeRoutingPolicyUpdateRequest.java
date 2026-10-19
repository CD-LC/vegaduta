package com.agenticai.core.agents.dto;

import com.agenticai.core.agents.core.EdgeRoutingPolicy;

/**
 * Edge Wave 2 (EDGE-B) - body of {@code PATCH /api/agents/{id}/edge-routing-policy}. {@code
 * edgeRoutingPolicy} null CLEARS the policy (back to "device preferences decide"), so unlike
 * {@code AgentTuningModeUpdateRequest} there is deliberately no {@code @NotNull} - clearing is
 * a first-class operation, same shape as {@code AgentExperienceModeUpdateRequest}'s
 * null-clears-the-override contract.
 */
public record AgentEdgeRoutingPolicyUpdateRequest(EdgeRoutingPolicy edgeRoutingPolicy) {
}
