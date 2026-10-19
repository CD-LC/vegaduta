package com.agenticai.core.google;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * Thrown when the platform operator hasn't set app.google.oauth.* (client id/secret/redirect-uri)
 * yet.
 */
public class GoogleOAuthNotConfiguredException extends RuntimeException
        implements HttpStatusMapped {

    public GoogleOAuthNotConfiguredException() {
        super("Google integration is not configured on this deployment yet.");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
