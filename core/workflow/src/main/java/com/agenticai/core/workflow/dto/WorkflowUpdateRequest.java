package com.agenticai.core.workflow.dto;

import com.agenticai.core.workflow.WorkflowStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code status} is set explicitly by the caller rather than inferred - the
 * canvas shows an explicit "Save as Draft" vs. "Activate" choice, and
 * flipping to {@link WorkflowStatus#ACTIVE} is exactly the action that must
 * route through {@code OrchestratorService#validate}'s full governance gate
 * (see {@code WorkflowService#update}), not something that should happen as
 * a side effect of an unrelated field changing.
 */
public record WorkflowUpdateRequest(
    @NotBlank(message = "name is required")
    @Size(max = 200, message = "name must be at most 200 characters") String name,
    String description,
    @NotNull(message = "graph is required") @Valid WorkflowGraphDto graph,
    @NotNull(message = "status is required") WorkflowStatus status
) {
}
