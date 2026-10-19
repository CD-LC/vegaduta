package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.omnichannel.ChannelDeliveryException;

/**
 * A call to the signal-cli REST API failed. {@code status} is the HTTP status, {@link #IO} when
 * the server could not be reached or answered too slowly, or {@link #REFUSED} when the outbound
 * request policy (SSRF guard) would not let the call out at all. The message never carries a
 * token.
 */
public class SignalApiException extends ChannelDeliveryException {

    public static final int IO = 0;
    public static final int REFUSED = -1;

    private final int status;

    public SignalApiException(int status, String message) {
        super("signal", message);
        this.status = status;
    }

    public SignalApiException(int status, String message, Throwable cause) {
        super("signal", message, cause);
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
