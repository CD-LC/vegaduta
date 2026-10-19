package com.agenticai.core.agents.tools.openapi.dto;

/**
 * "Show me what this document would import" - exactly one of {@code specUrl} / {@code specText} is
 * expected (the URL wins when both are given). Nothing is persisted; the same parser the real
 * import uses runs and the operations come back for the console's checkbox list.
 */
public record OpenApiPreviewRequest(
        String name, String toolPrefix, String specUrl, String specText) {}
