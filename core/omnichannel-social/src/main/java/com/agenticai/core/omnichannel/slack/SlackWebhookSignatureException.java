package com.agenticai.core.omnichannel.slack;

import com.agenticai.core.security.http.HttpStatusMapped;

public class SlackWebhookSignatureException extends RuntimeException implements HttpStatusMapped {

    public SlackWebhookSignatureException(String message) {
        super(message);
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
