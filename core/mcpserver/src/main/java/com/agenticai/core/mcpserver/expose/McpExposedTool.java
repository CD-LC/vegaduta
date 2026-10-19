package com.agenticai.core.mcpserver.expose;

import java.util.UUID;

/**
 * One dynamically advertised MCP tool, resolved for ONE authenticated caller by {@link
 * McpExposedToolCatalog}. {@code resourceId} is null only for the shared {@code get_run} tool.
 *
 * @param name            wire name the MCP client sees ({@code agent_<slug>}, {@code workflow_<slug>}, {@code get_run})
 * @param description     short, model-facing description - never the agent's system prompt or a workflow's graph
 * @param inputSchemaJson JSON Schema (as a JSON string) for the tool's arguments
 * @param target          what invoking it does
 * @param resourceId      the agent / workflow behind it
 * @param displayName     the resource's current name (for audit and error text)
 */
public record McpExposedTool(
    String name,
    String description,
    String inputSchemaJson,
    Target target,
    UUID resourceId,
    String displayName
) {

    public enum Target {
        AGENT,
        WORKFLOW,
        GET_RUN
    }
}
