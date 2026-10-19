package com.agenticai.core.workflow.dto;

/** Workflow diagram export formats - the request-a-diagram counterpart to the existing text/PDF run-output exports. */
public enum DiagramFormat {
    MERMAID,
    PLANTUML;

    public static DiagramFormat fromWire(String value) {
        if (value == null || value.isBlank()) {
            return MERMAID;
        }
        return DiagramFormat.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
