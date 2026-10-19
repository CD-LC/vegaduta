package com.agenticai.core.admin.keycloak;

/**
 * Keycloak refused a user creation because a user with the same unique field
 * (email or username) already exists - its raw response is a 409 carrying
 * {@code {"errorMessage":"User exists with same email"}}. Mapped to HTTP 409
 * with this exception's own already-clean message (see {@code
 * GlobalExceptionHandler}), so an admin gets "a user with this email already
 * exists" instead of the raw upstream JSON blob a generic {@link
 * KeycloakAdminException} 502 would surface.
 */
public class KeycloakUserConflictException extends KeycloakAdminException {

    public KeycloakUserConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
