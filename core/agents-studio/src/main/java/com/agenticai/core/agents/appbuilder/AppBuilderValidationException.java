package com.agenticai.core.agents.appbuilder;

/**
 * The request body to {@code POST /api/internal/app-builder/generate-files}
 * was missing, not valid JSON, or failed field validation (e.g. blank
 * {@code instruction}). Mapped to 400 by {@code GlobalExceptionHandler} -
 * distinct from {@link AppBuilderGenerationException}'s 502, which is
 * reserved for "the request was fine but the LLM call itself failed or
 * returned unusable output." A malformed caller request is app-builder's
 * bug to fix, not an upstream-provider failure - these must not share a
 * status code.
 */
public class AppBuilderValidationException extends RuntimeException {

    public AppBuilderValidationException(String message) {
        super(message);
    }
}
