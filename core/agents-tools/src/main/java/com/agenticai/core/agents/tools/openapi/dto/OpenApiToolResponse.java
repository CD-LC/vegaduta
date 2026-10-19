package com.agenticai.core.agents.tools.openapi.dto;

import com.agenticai.core.agents.tools.openapi.OpenApiTool;
import java.time.Instant;
import java.util.UUID;

public record OpenApiToolResponse(
        UUID id,
        UUID sourceId,
        String toolName,
        String operationKey,
        String operationId,
        String httpMethod,
        String pathTemplate,
        String summary,
        String description,
        String bodyMode,
        String inputSchemaJson,
        boolean enabled,
        boolean deprecated,
        Instant createdAt,
        Instant updatedAt) {
    public static OpenApiToolResponse from(OpenApiTool tool) {
        return new OpenApiToolResponse(
                tool.getId(),
                tool.getSource() == null ? null : tool.getSource().getId(),
                tool.getToolName(),
                tool.getOperationKey(),
                tool.getOperationId(),
                tool.getHttpMethod(),
                tool.getPathTemplate(),
                tool.getSummary(),
                tool.getDescription(),
                tool.getBodyMode() == null ? "NONE" : tool.getBodyMode().name(),
                tool.getInputSchemaJson(),
                tool.isEnabled(),
                tool.isDeprecated(),
                tool.getCreatedAt(),
                tool.getUpdatedAt());
    }
}
