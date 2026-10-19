package com.agenticai.core.agents.tools.openapi;

/**
 * A person-readable refusal from the OpenAPI import/execution path: an unparseable document, a spec
 * URL or base URL that fails the SSRF check, a missing credential, an unreachable API. Extends
 * {@link IllegalArgumentException} deliberately so that on the REST path (import, refresh, test) it
 * lands in {@code GlobalExceptionHandler}'s generic 400 handler with the real reason - every one of
 * these is a client-side mistake, not a server fault - while on the agent path {@link
 * OpenApiToolCallback} catches it and hands the model an {@code {"error": ...}} it can act on.
 */
public class OpenApiToolException extends IllegalArgumentException {

    public OpenApiToolException(String message) {
        super(message);
    }
}
