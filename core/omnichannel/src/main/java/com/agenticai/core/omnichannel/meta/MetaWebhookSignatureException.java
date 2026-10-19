package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.security.http.HttpStatusMapped;

public class MetaWebhookSignatureException extends RuntimeException implements HttpStatusMapped {

    public MetaWebhookSignatureException(String message) {
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
