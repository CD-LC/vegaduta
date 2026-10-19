package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.security.http.HttpStatusMapped;

public class MetaSocialNotConfiguredException extends RuntimeException implements HttpStatusMapped {

    public MetaSocialNotConfiguredException() {
        super(
                "No active Facebook Page connection configured - POST /api/channels/meta-social/credential first.");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
