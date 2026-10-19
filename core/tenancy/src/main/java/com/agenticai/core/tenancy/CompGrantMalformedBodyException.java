package com.agenticai.core.tenancy;

/**
 * DAST-2026-07-27 D4 fix - {@code POST /api/tenant/comp-grant}'s body could
 * not be parsed as JSON (or was empty). Thrown ONLY from {@code
 * CompGrantController#parseAndValidate}, which deliberately runs AFTER {@code
 * authenticate()} has already succeeded - see that controller's javadoc for
 * why the body is read as a raw {@code String} (not a typed {@code @Valid
 * @RequestBody}) precisely so this parse step, and the field-validation step
 * right after it, can never run before the service-token check does. Mapped
 * to 400 by {@code GlobalExceptionHandler}, same message the old {@code
 * HttpMessageNotReadableException} handler already used for this endpoint.
 */
public class CompGrantMalformedBodyException extends RuntimeException {

    public CompGrantMalformedBodyException(String message) {
        super(message);
    }
}
