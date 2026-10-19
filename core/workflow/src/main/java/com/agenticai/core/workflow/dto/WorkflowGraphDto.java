package com.agenticai.core.workflow.dto;

import java.util.List;

public record WorkflowGraphDto(
    List<WorkflowNodeDto> nodes,
    List<WorkflowEdgeDto> edges
) {

    public WorkflowGraphDto {
        nodes = nodes != null ? nodes : List.of();
        edges = edges != null ? edges : List.of();
    }
}
