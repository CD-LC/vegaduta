package com.agenticai.core.tenancy;

/**
 * Platform-admin "delete tenant completely" control - the caller presented
 * an {@code X-Master-Key} header whose value did not match the configured
 * {@code PLATFORM_MASTER_KEY}. Mapped to 403 by {@code
 * GlobalExceptionHandler} (contrast {@link MasterKeyMissingException}'s
 * 401). Deliberately logged at that handler - a stream of these can mean
 * someone is probing the endpoint, same posture as {@code
 * CompGrantTokenInvalidException}, which this class mirrors exactly.
 */
public class MasterKeyInvalidException extends RuntimeException {

    public MasterKeyInvalidException(String message) {
        super(message);
    }
}
