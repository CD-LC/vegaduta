package com.agenticai.core.providers.capability;

/**
 * Thrown when a capability-dependent call runs with nothing configured for
 * the tenant - no provider key, no tenant context to hold one - mapped to a
 * 400 by {@code GlobalExceptionHandler}, same "not configured yet" contract
 * {@code SarvamNotConfiguredException} carries. {@code
 * JevNotConfiguredException} extends this so the legacy {@code /api/jev}
 * surface and the generic {@code /api/capabilities} surface share one
 * handler-shaped contract.
 */
public class CapabilityNotConfiguredException extends RuntimeException {

    public CapabilityNotConfiguredException(String message) {
        super(message);
    }
}
