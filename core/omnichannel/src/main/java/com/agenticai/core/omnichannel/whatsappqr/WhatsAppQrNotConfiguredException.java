package com.agenticai.core.omnichannel.whatsappqr;

import com.agenticai.core.security.http.HttpStatusMapped;

public class WhatsAppQrNotConfiguredException extends RuntimeException implements HttpStatusMapped {

    public WhatsAppQrNotConfiguredException() {
        super("No active WhatsApp QR connection - link a device at /api/channels/whatsapp-qr/session first.");
    }

    public WhatsAppQrNotConfiguredException(String message) {
        super(message);
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
