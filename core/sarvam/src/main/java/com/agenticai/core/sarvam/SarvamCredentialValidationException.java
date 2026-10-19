package com.agenticai.core.sarvam;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * Thrown by {@link SarvamCredentialValidator} when Sarvam rejects a key (or a validation call can't
 * be completed) - deliberately its own type rather than reusing {@code
 * core.providers.validation.ProviderValidationException}: {@code core/sarvam} cannot depend on
 * {@code core/providers} without creating a module cycle ({@code core/providers} already depends on
 * {@code core/sarvam} for {@code ProviderCredentialResolver}'s SARVAM branch - see that class's
 * javadoc). {@code GlobalExceptionHandler} maps this to the same {@code 400 Bad Request} every
 * other provider's validation-failure exception gets, so callers see identical behavior either way.
 */
public class SarvamCredentialValidationException extends RuntimeException
        implements HttpStatusMapped {

    public SarvamCredentialValidationException(String message) {
        super(message);
    }

    public SarvamCredentialValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
