package com.agenticai.core.agents.tools.openapi.dto;

import com.agenticai.core.agents.tools.openapi.OpenApiSpecParser;
import java.util.List;

public record OpenApiPreviewResponse(
        String title,
        String version,
        String description,
        String serverUrl,
        String suggestedBaseUrl,
        String toolPrefix,
        List<OperationPreview> operations) {

    public record OperationPreview(
            String operationKey,
            String suggestedToolName,
            String operationId,
            String method,
            String path,
            String summary,
            String description,
            boolean deprecated,
            String bodyMode,
            String inputSchemaJson) {
        public static OperationPreview from(OpenApiSpecParser.ParsedOperation op) {
            return new OperationPreview(
                    op.operationKey(),
                    op.suggestedToolName(),
                    op.operationId(),
                    op.method(),
                    op.path(),
                    op.summary(),
                    op.description(),
                    op.deprecated(),
                    op.bodyMode().name(),
                    op.inputSchemaJson());
        }
    }

    public static OpenApiPreviewResponse from(
            OpenApiSpecParser.ParsedSpec spec, String suggestedBaseUrl, String toolPrefix) {
        return new OpenApiPreviewResponse(
                spec.title(),
                spec.version(),
                spec.description(),
                spec.serverUrl(),
                suggestedBaseUrl,
                toolPrefix,
                spec.operations().stream().map(OperationPreview::from).toList());
    }
}
