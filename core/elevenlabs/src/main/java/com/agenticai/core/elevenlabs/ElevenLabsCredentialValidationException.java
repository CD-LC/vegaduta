package com.agenticai.core.elevenlabs;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * Thrown by {@link ElevenLabsCredentialValidator} when ElevenLabs rejects a key (or a validation
 * call can't be completed) - own type rather than reusing {@code
 * core.providers.validation.ProviderValidationException} for the same module-cycle reason {@code
 * SarvamCredentialValidationException} documents. {@code GlobalExceptionHandler} maps this to
 * {@code 400 Bad Request}, same as every other provider's validation-failure exception.
 */
public class ElevenLabsCredentialValidationException extends RuntimeException
        implements HttpStatusMapped {

    public ElevenLabsCredentialValidationException(String message) {
        super(message);
    }

    public ElevenLabsCredentialValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
