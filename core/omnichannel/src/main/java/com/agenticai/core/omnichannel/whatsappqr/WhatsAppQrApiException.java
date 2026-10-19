package com.agenticai.core.omnichannel.whatsappqr;

import com.agenticai.core.omnichannel.ChannelDeliveryException;

/**
 * A call to the whatsapp-qr bridge failed. {@code status} is the HTTP status, {@link #IO} when the
 * bridge could not be reached or answered too slowly, or {@link #REFUSED} when the outbound request
 * policy (SSRF guard) would not let the call out at all. The message never carries the shared
 * secret or a session key.
 */
public class WhatsAppQrApiException extends ChannelDeliveryException {

    public static final int IO = 0;
    public static final int REFUSED = -1;

    private final int status;

    public WhatsAppQrApiException(int status, String message) {
        super("whatsapp-qr", message);
        this.status = status;
    }

    public WhatsAppQrApiException(int status, String message, Throwable cause) {
        super("whatsapp-qr", message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** Whether trying again later can succeed: network trouble, rate limiting or a server fault. */
    public boolean retryable() {
        return status == IO || status == 429 || status >= 500;
    }
}
