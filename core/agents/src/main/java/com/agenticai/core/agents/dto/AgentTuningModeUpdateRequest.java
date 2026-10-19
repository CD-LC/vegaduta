package com.agenticai.core.agents.dto;

import com.agenticai.core.agents.core.TuningMode;
import jakarta.validation.constraints.NotNull;

/**
 * PATCH /api/agents/{id}/tuning-mode - lets a customer switch an existing
 * agent between Simple (auto-tuned) and Manual generation control, the same
 * after-creation-tunability pattern as {@code AgentReasoningDepthUpdateRequest}.
 * Required - there's no "clear it" state distinct from explicitly setting
 * MANUAL. Switching to SIMPLE triggers {@code
 * GenerationParamsInferenceService} - see {@link TuningMode}'s javadoc.
 */
public record AgentTuningModeUpdateRequest(
    @NotNull(message = "tuningMode is required") TuningMode tuningMode
) {
}
