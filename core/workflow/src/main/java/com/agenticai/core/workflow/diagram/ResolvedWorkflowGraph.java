package com.agenticai.core.workflow.diagram;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import java.util.List;

/** Shared intermediate model consumed by every diagram export format (Mermaid/PlantUML text, PDF layout) so agent/tool/workflow name resolution only happens once per export. */
public record ResolvedWorkflowGraph(String workflowName, List<DiagramNode> nodes, List<WorkflowEdgeDto> edges) {
}
