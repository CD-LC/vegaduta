package com.agenticai.core.google;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * Any failed call to a Google REST API - OAuth token endpoints or the four product APIs
 * (Drive/Gmail/Calendar/Sheets).
 */
public class GoogleApiException extends RuntimeException implements HttpStatusMapped {

    public GoogleApiException(String message) {
        super(message);
    }

    public GoogleApiException(String message, Throwable cause) {
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
