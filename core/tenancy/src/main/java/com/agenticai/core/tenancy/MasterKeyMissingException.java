package com.agenticai.core.tenancy;

/**
 * Platform-admin "delete tenant completely" control - the caller presented
 * no {@code X-Master-Key} header at all. Mapped to 401 by {@code
 * GlobalExceptionHandler} (contrast {@link MasterKeyInvalidException}'s 403,
 * where a key was offered but did not match). Also thrown, deliberately, to
 * fail closed, when {@code PLATFORM_MASTER_KEY} is unset on this server -
 * same posture as {@code CompGrantTokenMissingException}, which this class
 * mirrors exactly (a dedicated type per concern, not reused across features,
 * matching this codebase's established convention).
 */
public class MasterKeyMissingException extends RuntimeException {

    public MasterKeyMissingException(String message) {
        super(message);
    }
}
