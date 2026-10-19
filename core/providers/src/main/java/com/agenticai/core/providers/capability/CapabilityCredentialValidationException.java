package com.agenticai.core.providers.capability;

/**
 * Thrown when a {@link ProviderCredentialValidator} rejects or cannot verify
 * a key a tenant tried to activate - mapped to a 400 by {@code
 * GlobalExceptionHandler}, the generic-registry version of {@code
 * ProviderValidationException}. Vendor validators may throw a subtype
 * carrying their own name ({@code JevCredentialValidationException}) so a
 * brand-specific endpoint keeps its distinct exception type while the
 * generic {@code /api/capabilities} path maps the same failure the same way.
 */
public class CapabilityCredentialValidationException extends RuntimeException {

    public CapabilityCredentialValidationException(String message) {
        super(message);
    }

    public CapabilityCredentialValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
