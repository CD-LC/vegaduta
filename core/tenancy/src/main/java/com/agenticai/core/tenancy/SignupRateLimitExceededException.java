package com.agenticai.core.tenancy;

/** Thrown by {@link TenantSignupController} when {@link SignupRateLimiter#tryAcquire} rejects a caller. */
public class SignupRateLimitExceededException extends RuntimeException {

    public SignupRateLimitExceededException(String message) {
        super(message);
    }
}
