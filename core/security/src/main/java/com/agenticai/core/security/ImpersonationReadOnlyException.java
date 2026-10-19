package com.agenticai.core.security;

import java.util.UUID;

/**
 * A side-effecting action was attempted during a platform admin's read-only "view as user" session.
 * Mapped to 403 {@code {"error":"impersonation_read_only"}} by {@code GlobalExceptionHandler};
 * {@link ViewAsFilter} writes the same body itself for requests it rejects before dispatch.
 */
public class ImpersonationReadOnlyException extends RuntimeException {

    public static final String ERROR_CODE = "impersonation_read_only";

    private final String action;

    public ImpersonationReadOnlyException(String action, UUID sessionId) {
        super(
                "Read-only view-as session "
                        + sessionId
                        + ": '"
                        + action
                        + "' is not allowed while viewing as a user.");
        this.action = action;
    }

    public String getAction() {
        return action;
    }
}
