package com.agenticai.core.google;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * Thrown when a tool call or API request needs a connected Google account but this tenant has none
 * active.
 */
public class GoogleNotConnectedException extends RuntimeException implements HttpStatusMapped {

    public GoogleNotConnectedException() {
        super(
                "No Google account connected for this tenant - connect one on the Integrations page first.");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
