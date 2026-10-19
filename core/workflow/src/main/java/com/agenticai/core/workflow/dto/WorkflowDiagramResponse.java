package com.agenticai.core.workflow.dto;

/** {@code source} is the raw diagram text (Mermaid flowchart or PlantUML state-diagram syntax) - the canvas renders it client-side and/or offers it as a copyable/downloadable file. */
public record WorkflowDiagramResponse(String workflowId, String workflowName, DiagramFormat format, String source) {
}
