package com.agenticai.core.providers.validation;

import com.agenticai.core.providers.ProviderName;

/**
 * Thrown when activation is requested for a {@link ProviderName} that has no
 * {@link ProviderValidator} registered yet. Maps to HTTP 400.
 *
 * <p>MVP (Stage 2) ships Anthropic only; the other enum values exist so the
 * API contract doesn't change shape as OpenAI/Azure/Vertex/Bedrock/Ollama
 * validators are added in later stages.
 */
public class ProviderNotSupportedException extends RuntimeException {

    public ProviderNotSupportedException(ProviderName providerName) {
        super("Provider " + providerName + " is not yet supported in this build.");
    }
}
