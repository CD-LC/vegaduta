package com.agenticai.core.agents.tools.workflowdraft;

import org.springframework.ai.tool.ToolCallback;

/**
 * Port that builds the {@code create_workflow} tool.
 *
 * <p>Workflows live in {@code core/workflow}, which depends on {@code core/agents-tools}; the tool
 * resolver here ({@code AgentWorkflowDraftToolResolver}) must not depend back on it. {@code
 * CreateWorkflowToolCallbackFactory} in {@code core/workflow} implements this over {@code
 * WorkflowCrudService} and {@code WorkflowGraphGeneratorService}.
 */
public interface WorkflowDraftToolFactory {

    /** The tool's name, the per-agent allow-list entry that opts an agent in. */
    String TOOL_NAME = "create_workflow";

    /**
     * The tool, or {@code null} when it is switched off ({@code app.tools.create-workflow.enabled})
     * or the workflow services are not available in this context.
     */
    ToolCallback createIfEnabled();
}
