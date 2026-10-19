package com.agenticai.core.providers;

/** Thrown by {@link PlatformHostedLlmProviderService} for an unknown row id. Maps to HTTP 404. */
public class PlatformHostedLlmProviderNotFoundException extends RuntimeException {

    public PlatformHostedLlmProviderNotFoundException(Long id) {
        super("No hosted LLM provider with id " + id);
    }
}
