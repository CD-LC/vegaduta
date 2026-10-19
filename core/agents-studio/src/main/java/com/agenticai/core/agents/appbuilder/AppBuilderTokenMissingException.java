package com.agenticai.core.agents.appbuilder;

/**
 * {@code POST /api/internal/app-builder/generate-files} - the caller
 * presented no {@code X-Service-Token} header at all. Mapped to 401 by
 * {@code GlobalExceptionHandler}: no credential was offered (contrast
 * {@link AppBuilderTokenInvalidException}'s 403). Also thrown - deliberately,
 * to fail closed - when the configured token is unset on this server, so an
 * unconfigured deployment rejects every call rather than accepting any
 * token. Same shape as {@code CompGrantTokenMissingException}
 * (core/tenancy) - the established precedent for a service-to-service,
 * non-JWT endpoint in this codebase.
 */
public class AppBuilderTokenMissingException extends RuntimeException {

    public AppBuilderTokenMissingException(String message) {
        super(message);
    }
}
