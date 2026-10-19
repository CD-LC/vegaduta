package com.agenticai.core.tenancy;

/**
 * DAST-2026-07-27 D4 fix - {@code POST /api/tenant/comp-grant}'s body parsed
 * as JSON but failed one or more {@code jakarta.validation} constraints on
 * {@code CompGrantRequest} (missing {@code tenantId}/{@code targetPlan}, a
 * non-positive {@code durationMonths}, ...). Thrown ONLY from {@code
 * CompGrantController#parseAndValidate}, which runs AFTER {@code
 * authenticate()} has already succeeded - see {@link
 * CompGrantMalformedBodyException} and the controller's javadoc for the full
 * reasoning (an unauthenticated caller with a malformed/incomplete body used
 * to learn the exact required-field schema via a 400 raised during Spring's
 * {@code @Valid} argument-resolution step, which ran BEFORE the service-token
 * check; the fix defers all body parsing/validation until after auth passes).
 * Mapped to 400 by {@code GlobalExceptionHandler}, same field-level message
 * shape {@code MethodArgumentNotValidException} used to produce here.
 */
public class CompGrantValidationException extends RuntimeException {

    public CompGrantValidationException(String message) {
        super(message);
    }
}
