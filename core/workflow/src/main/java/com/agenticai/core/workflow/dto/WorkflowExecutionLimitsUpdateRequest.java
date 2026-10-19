package com.agenticai.core.workflow.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * PATCH /api/workflows/{workflowId}/execution-limits - sets hard per-run
 * ceilings on step count and per-step wall-clock time. Every field is
 * optional and a null field leaves the stored value unchanged (at least one
 * must be present); a supplied field is range-checked on its own, so the
 * error names the field that is wrong. Bounds follow the ranges documented for
 * {@link com.agenticai.core.workflow.WorkflowExecutionParamsInferenceService.InferredLimits}:
 * 1–200 steps, 1–300 seconds per step.
 */
public record WorkflowExecutionLimitsUpdateRequest(
    @Min(value = 1, message = "maxStepsPerRun must be at least 1")
    @Max(value = 200, message = "maxStepsPerRun must be at most 200")
    Integer maxStepsPerRun,

    @Min(value = 1, message = "stepTimeoutSeconds must be at least 1")
    @Max(value = 300, message = "stepTimeoutSeconds must be at most 300")
    Integer stepTimeoutSeconds,

    // Number of RE-attempts after a node's first try (0 = never retry, 5 =
    // up to 6 total attempts) - see WorkflowDefinition#maxRetryAttempts's
    // javadoc for why this is retries-after-first, not total attempts.
    @Min(value = 0, message = "maxRetryAttempts must be at least 0")
    @Max(value = 5, message = "maxRetryAttempts must be at most 5")
    Integer maxRetryAttempts
) {
}
