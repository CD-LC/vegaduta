package com.agenticai.core.agents.appbuilder;

/**
 * {@code POST /api/internal/app-builder/generate-files} - the caller
 * presented an {@code X-Service-Token} header whose value did not match the
 * configured secret. Mapped to 403 by {@code GlobalExceptionHandler}: a
 * credential was offered but rejected (contrast
 * {@link AppBuilderTokenMissingException}'s 401). Logged at that handler -
 * a stream of these can mean someone is probing the endpoint. Same shape as
 * {@code CompGrantTokenInvalidException} (core/tenancy).
 */
public class AppBuilderTokenInvalidException extends RuntimeException {

    public AppBuilderTokenInvalidException(String message) {
        super(message);
    }
}
