package com.agenticai.core.eventbus.webhook;

/** Mapped to HTTP 404 by {@code GlobalExceptionHandler} (same convention as the other *NotFoundException classes). */
public class WebhookSubscriptionNotFoundException extends RuntimeException {

    public WebhookSubscriptionNotFoundException(String id) {
        super("Webhook subscription not found: " + id);
    }
}
