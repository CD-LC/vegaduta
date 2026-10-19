package com.agenticai.core.agents.dto;

import com.agenticai.core.agents.core.ReasoningDepth;
import jakarta.validation.constraints.NotNull;

/**
 * PATCH /api/agents/{id}/reasoning-depth - lets a customer opt an existing
 * agent into rigorous, independently-verified tool calls (see {@link
 * ReasoningDepth}'s javadoc), the same after-creation-tunability pattern as
 * {@code AgentGenerationParamsUpdateRequest}. Required (not nullable, unlike
 * {@code experienceMode}'s PATCH) - there's no "clear it" state distinct
 * from explicitly setting FAST.
 */
public record AgentReasoningDepthUpdateRequest(
    @NotNull(message = "reasoningDepth is required") ReasoningDepth reasoningDepth
) {
}
