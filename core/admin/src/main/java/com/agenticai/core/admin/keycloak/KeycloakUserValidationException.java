package com.agenticai.core.admin.keycloak;

/**
 * Keycloak refused a user creation on its declarative User Profile validation
 * - its raw response is a 400 carrying e.g. {@code {"field":"username",
 * "errorMessage":"error-invalid-length","params":["username",3,255]}}. Mapped
 * to HTTP 400 with a human-readable message (see {@code GlobalExceptionHandler})
 * so an admin sees "Username must be between 3 and 255 characters", not an
 * upstream error code buried in JSON inside a 502.
 */
public class KeycloakUserValidationException extends KeycloakAdminException {

    public KeycloakUserValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
