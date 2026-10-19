package com.agenticai.core.omnichannel.x;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * X's API (OAuth token endpoint or v2 REST endpoints) returned an error or was unreachable - same
 * role as {@code GoogleApiException}.
 */
public class XApiException extends RuntimeException implements HttpStatusMapped {

    public XApiException(String message) {
        super(message);
    }

    public XApiException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 502: an outbound call to the third-party API failed. Answered by the central exception
     * handler.
     */
    @Override
    public int httpStatus() {
        return 502;
    }

    @Override
    public boolean logAtWarn() {
        return true;
    }
}
