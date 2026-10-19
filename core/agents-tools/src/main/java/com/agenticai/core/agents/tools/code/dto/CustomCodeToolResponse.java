package com.agenticai.core.agents.tools.code.dto;

import com.agenticai.core.agents.tools.code.CustomCodeTool;
import com.agenticai.core.agents.tools.code.CustomCodeToolLanguage;
import com.agenticai.core.agents.tools.code.CustomCodeToolStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Full read shape - deliberately includes {@code sourceCode}: this is what the editor loads to let
 * a tenant continue working on their own program, so omitting it would make the list endpoint
 * useless to the only UI that calls it. {@code status} + {@code validationError} are what the
 * editor renders as inline diagnostics after a rejected save.
 */
public record CustomCodeToolResponse(
        UUID id,
        String toolName,
        String displayName,
        String description,
        CustomCodeToolLanguage language,
        String sourceCode,
        String inputSchemaJson,
        String outputDescription,
        boolean enabled,
        CustomCodeToolStatus status,
        String validationError,
        String sourceSha256,
        int timeoutMs,
        int memoryMb,
        int version,
        /**
         * Vault secret NAMES this tool may receive - never values, and there is no endpoint
         * anywhere that would return one. The editor renders these so a reader can answer "what can
         * this program reach" without reading the program.
         */
        Set<String> allowedSecretNames,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
    public static CustomCodeToolResponse from(CustomCodeTool tool) {
        return new CustomCodeToolResponse(
                tool.getId(),
                tool.getToolName(),
                tool.getDisplayName(),
                tool.getDescription(),
                tool.getLanguage(),
                tool.getSourceCode(),
                tool.getInputSchemaJson(),
                tool.getOutputDescription(),
                tool.isEnabled(),
                tool.getStatus(),
                tool.getValidationError(),
                tool.getSourceSha256(),
                tool.getTimeoutMs(),
                tool.getMemoryMb(),
                tool.getVersion(),
                Set.copyOf(tool.getAllowedSecretNames()),
                tool.getCreatedBy(),
                tool.getCreatedAt(),
                tool.getUpdatedAt());
    }
}
