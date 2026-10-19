package com.agenticai.core.agents.tools.script.dto;

import com.agenticai.core.agents.tools.script.UserToolScript;
import java.time.Instant;
import java.util.UUID;

public record UserToolScriptResponse(
        UUID id,
        String toolName,
        String displayName,
        String description,
        String expression,
        String inputSchemaJson,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {
    public static UserToolScriptResponse from(UserToolScript script) {
        return new UserToolScriptResponse(
                script.getId(),
                script.getToolName(),
                script.getDisplayName(),
                script.getDescription(),
                script.getExpression(),
                script.getInputSchemaJson(),
                script.isEnabled(),
                script.getCreatedAt(),
                script.getUpdatedAt());
    }
}
