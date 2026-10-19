package com.agenticai.core.billing.gateway;

/**
 * The gateway webhook signature verification step failed - either the
 * signature genuinely didn't match, or verification itself couldn't run
 * (e.g. a misconfigured webhook secret). Either way, treated identically by
 * the caller: reject the request. Same posture as
 * {@code TwilioWebhookSignatureException} for the inbound WhatsApp webhook -
 * a stream of these could mean someone is probing the endpoint, not just an
 * honest misconfiguration, so Stage 4's controller logs this one explicitly.
 */
public class InvalidWebhookSignatureException extends RuntimeException {

    public InvalidWebhookSignatureException(String message) {
        super(message);
    }
}
