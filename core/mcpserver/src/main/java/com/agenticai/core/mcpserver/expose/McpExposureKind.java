package com.agenticai.core.mcpserver.expose;

import java.util.Locale;

/**
 * Which kind of tenant resource an {@link McpToolExposure} row refers to. {@link #toolPrefix()} is
 * the stable, human-readable prefix every tool name of that kind starts with ({@code agent_...},
 * {@code workflow_...}); none of this module's static tools ({@code list_my_agents},
 * {@code call_agent}, {@code list_my_workflows}, {@code call_workflow}, {@code search_knowledge},
 * and the async-task tools) starts with either prefix, so a dynamic tool can never shadow one.
 */
public enum McpExposureKind {
    AGENT("agent_"),
    WORKFLOW("workflow_");

    private final String toolPrefix;

    McpExposureKind(String toolPrefix) {
        this.toolPrefix = toolPrefix;
    }

    public String toolPrefix() {
        return toolPrefix;
    }

    /** Parses the URL segment used by the exposure API ({@code agents} / {@code workflows}); throws {@link IllegalArgumentException} (HTTP 400) otherwise. */
    public static McpExposureKind fromPathSegment(String segment) {
        String normalized = segment == null ? "" : segment.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "agents", "agent" -> AGENT;
            case "workflows", "workflow" -> WORKFLOW;
            default -> throw new IllegalArgumentException("Unknown resource type '" + segment + "' - use 'agents' or 'workflows'.");
        };
    }
}
