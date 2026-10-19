package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.security.http.HttpStatusMapped;

public class MetaWhatsAppNotConfiguredException extends RuntimeException
        implements HttpStatusMapped {

    public MetaWhatsAppNotConfiguredException() {
        super(
                "No active Meta WhatsApp Cloud API credential configured - POST /api/channels/meta-whatsapp/credential first.");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
