package com.agenticai.core.google;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * The OAuth `state` round-tripped through Google didn't decrypt/verify, or was too old - see
 * GoogleOAuthService#decodeState.
 */
public class GoogleOAuthStateException extends RuntimeException implements HttpStatusMapped {

    public GoogleOAuthStateException(String message) {
        super(message);
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
