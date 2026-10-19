package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.security.http.HttpStatusMapped;

public class ImessageNotConfiguredException extends RuntimeException implements HttpStatusMapped {

    public ImessageNotConfiguredException() {
        super("No active iMessage (BlueBubbles) server connected - POST /api/channels/imessage/credential first.");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
