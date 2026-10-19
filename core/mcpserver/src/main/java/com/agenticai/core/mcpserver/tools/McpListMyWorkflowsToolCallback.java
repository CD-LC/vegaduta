package com.agenticai.core.mcpserver.tools;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.mcpserver.McpCallerContext;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The workflow-side mirror of {@link McpListAgentsToolCallback} - see that
 * class's javadoc for why this exists as a small discovery "meta-tool"
 * rather than one real MCP tool per exposed workflow ({@code
 * McpServerToolConfig}'s javadoc has the full citation). Lists which
 * workflow ids/names this API key's grant has opted into {@code
 * McpServerAccessGrant#exposedWorkflowIds} - the first tool in this build to
 * actually read that field (see that entity's javadoc for the "persisted
 * but not yet enforced" gap this, together with {@link
 * McpCallWorkflowToolCallback}, closes).
 */
public class McpListMyWorkflowsToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(McpListMyWorkflowsToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final WorkflowService workflowService;

    public McpListMyWorkflowsToolCallback(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("list_my_workflows")
            .description(
                "Lists the workflows this API key is allowed to trigger, each with its id, name, and status. "
                    + "Call this first, then pass one of the returned ids as call_workflow's workflow_id parameter."
            )
            .inputSchema("""
                {
                    "type": "object",
                    "properties": {}
                }
                """)
            .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            java.util.Set<UUID> exposed = McpCallerContext.current()
                .map(McpCallerContext::exposedWorkflowIds)
                .orElse(java.util.Set.of());
            List<Map<String, String>> workflows = resolveExposedWorkflows(workflowService, exposed).stream()
                .map(workflow -> Map.of(
                    "id", workflow.getId().toString(),
                    "name", workflow.getName(),
                    "status", workflow.getStatus().name()
                ))
                .toList();
            return MAPPER.writeValueAsString(workflows);
        } catch (Exception e) {
            log.warn("list_my_workflows tool call failed", e);
            return "{\"error\": \"Could not list workflows: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Workflow-side mirror of {@link McpListAgentsToolCallback
     * #resolveExposedAgents} - shared with {@code DeveloperApiController}'s
     * {@code GET /api/dev/v1/workflows} for the same no-drift reason.
     */
    public static List<WorkflowDefinition> resolveExposedWorkflows(
        WorkflowService workflowService, java.util.Set<UUID> exposedIds
    ) {
        return exposedIds.stream()
            .flatMap(id -> {
                try {
                    return java.util.stream.Stream.of(workflowService.get(id));
                } catch (RuntimeException e) {
                    // Exposed id no longer resolves (workflow deleted since the grant was
                    // configured) - skip it rather than fail the whole listing, same posture
                    // as McpListAgentsToolCallback.
                    log.warn("MCP grant references workflow {} which no longer exists - omitting from listing", id);
                    return java.util.stream.Stream.<WorkflowDefinition>empty();
                }
            })
            .toList();
    }
}
