package com.agenticai.core.workflow.dto;

import com.agenticai.core.agents.core.TuningMode;
import jakarta.validation.constraints.NotNull;

/**
 * PATCH /api/workflows/{workflowId}/tuning-mode - switches a workflow between
 * SIMPLE (execution limits inferred automatically from the workflow's name and
 * description via {@link com.agenticai.core.workflow.WorkflowExecutionParamsInferenceService})
 * and MANUAL (limits stay at whatever the customer last set explicitly).
 * Mirrors the {@code AgentTuningModeUpdateRequest} pattern in the agents module.
 */
public record WorkflowTuningModeUpdateRequest(
    @NotNull(message = "tuningMode is required") TuningMode tuningMode
) {
}
