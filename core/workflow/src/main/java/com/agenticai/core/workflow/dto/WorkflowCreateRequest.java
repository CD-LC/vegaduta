package com.agenticai.core.workflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WorkflowCreateRequest(
    @NotBlank(message = "name is required")
    @Size(max = 200, message = "name must be at most 200 characters") String name,
    String description,
    @NotNull(message = "graph is required") @Valid WorkflowGraphDto graph
) {
}
