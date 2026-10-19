package com.agenticai.core.omnichannel.x;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * The X Developer App is registered (step 1) but no X account has completed the OAuth consent yet
 * (step 2) - see {@code XCredential}'s javadoc.
 */
public class XNotConnectedException extends RuntimeException implements HttpStatusMapped {

    public XNotConnectedException() {
        super(
                "An X Developer App is registered, but no X account is connected yet - complete the OAuth connection first.");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
