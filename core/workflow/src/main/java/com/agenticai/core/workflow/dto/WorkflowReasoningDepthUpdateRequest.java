package com.agenticai.core.workflow.dto;

import com.agenticai.core.agents.core.ReasoningDepth;
import jakarta.validation.constraints.NotNull;

/**
 * PATCH /api/workflows/{workflowId}/reasoning-depth - mirrors the identical
 * {@code AgentReasoningDepthUpdateRequest} in the agents module (same field,
 * same constraint, same "no clear-it state" reasoning).
 */
public record WorkflowReasoningDepthUpdateRequest(
    @NotNull(message = "reasoningDepth is required") ReasoningDepth reasoningDepth
) {
}
