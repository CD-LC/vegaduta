package com.agenticai.core.providers.capability;

/**
 * Thrown when a {@code /api/capabilities/...} call names a capability or
 * provider the registry doesn't know - mapped to a 400 by {@code
 * GlobalExceptionHandler}, same "you asked for something that doesn't exist"
 * contract {@code ProviderNotSupportedException} carries on the LLM path.
 */
public class UnknownCapabilityException extends RuntimeException {

    public UnknownCapabilityException(String message) {
        super(message);
    }
}
