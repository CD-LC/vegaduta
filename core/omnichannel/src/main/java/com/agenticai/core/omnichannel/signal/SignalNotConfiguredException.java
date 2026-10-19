package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.security.http.HttpStatusMapped;

public class SignalNotConfiguredException extends RuntimeException implements HttpStatusMapped {

    public SignalNotConfiguredException() {
        super("No active Signal connection - POST /api/channels/signal/credential first.");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
