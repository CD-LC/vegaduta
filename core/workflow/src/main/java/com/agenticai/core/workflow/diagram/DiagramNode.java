package com.agenticai.core.workflow.diagram;

/** A workflow graph node resolved down to what a diagram actually needs to show - a human-readable label (agent name/model, tool name, condition text, etc., not a raw UUID) plus enough shape info to render consistently across Mermaid, PlantUML, and the PDF renderer. */
public record DiagramNode(String id, String label, boolean branching, String styleClass) {
}
