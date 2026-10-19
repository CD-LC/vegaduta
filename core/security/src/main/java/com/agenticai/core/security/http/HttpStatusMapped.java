package com.agenticai.core.security.http;

/**
 * An exception that declares its own HTTP mapping, so the central exception handler ({@code
 * common.GlobalExceptionHandler}) can answer it without importing the module that throws it
 * (ADR-0011 rule a: vendor modules own their exceptions, the kernel handler stays vendor-free). The
 * response is the handler's usual error body with the exception's message; {@link #errorCode()},
 * when present, adds the stable {@code code} member and drives the problem {@code type}.
 */
public interface HttpStatusMapped {

    /**
     * The HTTP status to answer with, e.g. 400 for "not configured yet", 502 for a failed outbound
     * call.
     */
    int httpStatus();

    /**
     * A stable machine-readable code for the body's {@code code} member, or {@code null} for none.
     */
    default String errorCode() {
        return null;
    }

    /**
     * Whether the handler logs this at WARN: outbound-call failures (5xx, logged with the stack
     * trace) and rejected webhook signatures (403, a stream of which may mean probing). Plain
     * client errors are not logged.
     */
    default boolean logAtWarn() {
        return false;
    }
}
