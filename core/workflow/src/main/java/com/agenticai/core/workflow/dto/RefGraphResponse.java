package com.agenticai.core.workflow.dto;

import java.util.List;

/**
 * Result of {@code WorkflowGraphGeneratorService#generateWithRefs}: a graph whose agent /
 * knowledge ids are the caller's refs (NOT validated against real entities - see that
 * method), plus the dependency arrays the model listed alongside it. All lists are never null.
 */
public record RefGraphResponse(
    WorkflowGraphDto graph,
    List<String> notes,
    List<String> secretsNeeded,
    List<String> mcpNeeded,
    List<String> knowledgeNeeded
) {
    public RefGraphResponse {
        notes = notes != null ? notes : List.of();
        secretsNeeded = secretsNeeded != null ? secretsNeeded : List.of();
        mcpNeeded = mcpNeeded != null ? mcpNeeded : List.of();
        knowledgeNeeded = knowledgeNeeded != null ? knowledgeNeeded : List.of();
    }
}
