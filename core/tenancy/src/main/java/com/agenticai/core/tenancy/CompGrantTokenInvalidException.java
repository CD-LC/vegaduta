package com.agenticai.core.tenancy;

/**
 * Academy Fast-Track Scholarship one-click fulfilment - the {@code
 * POST /api/tenant/comp-grant} caller presented an {@code X-Service-Token}
 * header whose value did not match the configured {@code CORE_COMP_SERVICE_TOKEN}.
 * Mapped to 403 by {@code GlobalExceptionHandler}: a credential was offered but
 * rejected (contrast {@link CompGrantTokenMissingException}'s 401, where none was
 * offered). Deliberately logged at that handler, like the webhook-signature
 * rejections, since a stream of these can mean someone is probing the endpoint.
 */
public class CompGrantTokenInvalidException extends RuntimeException {

    public CompGrantTokenInvalidException(String message) {
        super(message);
    }
}
