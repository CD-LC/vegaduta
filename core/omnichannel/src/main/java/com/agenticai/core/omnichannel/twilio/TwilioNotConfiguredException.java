package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.security.http.HttpStatusMapped;

public class TwilioNotConfiguredException extends RuntimeException implements HttpStatusMapped {

    public TwilioNotConfiguredException() {
        super(
                "No active Twilio credential configured - POST /api/channels/twilio/credential first.");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
