package com.agenticai.core.agents.tools.openapi;

import java.util.UUID;

/**
 * What a chat turn needs from one runnable {@link OpenApiTool}: the tool definition the model sees
 * and the id to run it by. The imported spec text, the auth settings and the encrypted secret of
 * the source are deliberately not here: a call re-reads the row ({@link
 * OpenApiToolSourceService#findRunnable}), so an edit or a disable takes effect on the next call
 * and nothing sensitive sits in a cache. An immutable value built inside the loading transaction,
 * so it is safe to cache per pod.
 */
public record OpenApiToolSnapshot(
        UUID id,
        String toolName,
        String summary,
        String description,
        String httpMethod,
        String pathTemplate,
        String inputSchemaJson,
        boolean deprecated,
        String sourceName) {

    static OpenApiToolSnapshot from(OpenApiTool tool) {
        return new OpenApiToolSnapshot(
                tool.getId(),
                tool.getToolName(),
                tool.getSummary(),
                tool.getDescription(),
                tool.getHttpMethod(),
                tool.getPathTemplate(),
                tool.getInputSchemaJson(),
                tool.isDeprecated(),
                tool.getSource() == null ? null : tool.getSource().getName());
    }
}
