package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.omnichannel.ChannelException;
import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * Thrown when an inbound webhook request either has no {@code X-Twilio-Signature} header or the
 * signature doesn't match - the request either didn't come from Twilio, or {@code
 * app.omnichannel.public-base-url} doesn't match the URL Twilio actually signed against (the
 * classic cause behind a reverse proxy / ngrok tunnel). Mapped to 403 by {@code
 * GlobalExceptionHandler} - this is the one endpoint in the app that authenticates the caller by
 * proving they hold the Twilio account's auth token, not a Keycloak JWT (see {@code
 * SecurityConfig}'s {@code /webhook/**} permitAll rule).
 */
public class TwilioWebhookSignatureException extends ChannelException implements HttpStatusMapped {

    public TwilioWebhookSignatureException(String message) {
        super("whatsapp", message);
    }

    /**
     * 403: the request's claimed identity did not check out. Answered by the central exception
     * handler.
     */
    @Override
    public int httpStatus() {
        return 403;
    }

    @Override
    public boolean logAtWarn() {
        return true;
    }
}
