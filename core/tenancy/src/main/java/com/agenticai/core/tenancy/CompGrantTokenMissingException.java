package com.agenticai.core.tenancy;

/**
 * Academy Fast-Track Scholarship one-click fulfilment - the {@code
 * POST /api/tenant/comp-grant} caller presented no {@code X-Service-Token}
 * header at all. Mapped to 401 by {@code GlobalExceptionHandler}: no credential
 * was offered (contrast {@link CompGrantTokenInvalidException}'s 403, where a
 * credential was offered but did not match). Also thrown - deliberately, to fail
 * closed - when {@code CORE_COMP_SERVICE_TOKEN} is unset on this server, so an
 * unconfigured deployment rejects every comp-grant call rather than accepting
 * any token (see {@code CompGrantController}).
 */
public class CompGrantTokenMissingException extends RuntimeException {

    public CompGrantTokenMissingException(String message) {
        super(message);
    }
}
