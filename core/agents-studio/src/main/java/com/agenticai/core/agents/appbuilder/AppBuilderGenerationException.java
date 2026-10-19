package com.agenticai.core.agents.appbuilder;

/**
 * The LLM call itself failed, or its reply could not be parsed as the
 * expected file-list JSON after the one corrective retry
 * ({@link AppBuilderCompletionService}). Mapped to 502 by
 * {@code GlobalExceptionHandler} - this server successfully authenticated
 * and routed the call, but the upstream model/provider did not produce a
 * usable result, which is a "the LLM leg failed" class of error, not a bad
 * request from app-builder.
 */
public class AppBuilderGenerationException extends RuntimeException {

    public AppBuilderGenerationException(String message) {
        super(message);
    }

    public AppBuilderGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
