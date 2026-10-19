package com.agenticai.core.providers.validation;

/**
 * Thrown when a submitted API key is rejected by the provider, or the
 * validation call itself cannot be completed. Maps to HTTP 400 in
 * {@link com.agenticai.core.common.GlobalExceptionHandler}.
 */
public class ProviderValidationException extends RuntimeException {

    public ProviderValidationException(String message) {
        super(message);
    }

    public ProviderValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
