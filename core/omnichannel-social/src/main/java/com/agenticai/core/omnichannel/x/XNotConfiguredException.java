package com.agenticai.core.omnichannel.x;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * No X Developer App has been registered yet (step 1 of 2 - see {@code XCredential}'s javadoc) -
 * thrown before any OAuth redirect could even be built.
 */
public class XNotConfiguredException extends RuntimeException implements HttpStatusMapped {

    public XNotConfiguredException() {
        super(
                "No X Developer App registered - POST /api/channels/x/credential with your Client ID/Secret first.");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
