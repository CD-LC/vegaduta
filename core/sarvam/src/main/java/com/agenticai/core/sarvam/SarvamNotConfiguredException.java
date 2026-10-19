package com.agenticai.core.sarvam;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * Thrown by {@link SarvamCredentialService#resolveActiveApiKey()} when no tenant credential is
 * active yet - mirrors {@code TwilioNotConfiguredException}.
 */
public class SarvamNotConfiguredException extends RuntimeException implements HttpStatusMapped {

    public SarvamNotConfiguredException() {
        super("No active Sarvam AI credential configured - POST /api/sarvam/credential first.");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
