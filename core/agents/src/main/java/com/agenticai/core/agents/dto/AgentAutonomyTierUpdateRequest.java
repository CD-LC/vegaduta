package com.agenticai.core.agents.dto;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import jakarta.validation.constraints.NotNull;

/**
 * PATCH /api/agents/{id}/autonomy-tier - lets a customer opt an existing
 * agent into a stricter or looser autonomy posture (see {@link
 * AgentAutonomyTier}'s javadoc), the same after-creation-tunability pattern
 * as {@link AgentReasoningDepthUpdateRequest}. Required (not nullable) -
 * there's no "clear it" state distinct from explicitly setting
 * SEMI_AUTONOMOUS.
 */
public record AgentAutonomyTierUpdateRequest(
    @NotNull(message = "autonomyTier is required") AgentAutonomyTier autonomyTier
) {
}
