package com.agenticai.core.mcpserver;

import com.agenticai.core.agents.tools.ToolCallLog;

/**
 * Maps {@link ToolCallLog} rows to the grant tool-call API view, keeping the entity out of the
 * controller.
 */
final class McpGrantToolCallViewMapper {

    private McpGrantToolCallViewMapper() {}

    static McpServerAccessController.McpGrantToolCallView toView(ToolCallLog log) {
        return new McpServerAccessController.McpGrantToolCallView(
                log.getId(),
                log.getAgentId(),
                log.getSessionId(),
                log.getToolName(),
                log.getToolInput(),
                log.getToolOutput(),
                log.getErrorMessage(),
                log.getStartedAt(),
                log.getCompletedAt());
    }
}
