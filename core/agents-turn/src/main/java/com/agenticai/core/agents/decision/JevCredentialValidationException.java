package com.agenticai.core.agents.decision;

import com.agenticai.core.providers.capability.CapabilityCredentialValidationException;

/**
 * Thrown when {@link JevCredentialValidator} rejects or cannot verify a key a
 * tenant tried to activate - mapped to a 400 by {@code GlobalExceptionHandler},
 * same "key rejected, nothing persisted" contract {@code
 * SarvamCredentialValidationException} carries. A subtype of the generic
 * {@link CapabilityCredentialValidationException} rather than a standalone
 * type: the {@code /api/jev} compat shim keeps its distinct exception, and
 * the generic {@code /api/capabilities} path maps the same failure the same
 * way.
 */
public class JevCredentialValidationException extends CapabilityCredentialValidationException {

    public JevCredentialValidationException(String message) {
        super(message);
    }

    public JevCredentialValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
