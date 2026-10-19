package com.agenticai.core.dbauth;

import java.time.Instant;

/**
 * A short-lived database credential and the instant after which the server rejects it for new
 * logins.
 */
public record DbToken(String value, Instant expiresAt) {
    public DbToken {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("empty database token");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("database token without an expiry");
        }
    }

    /** Never print the credential. */
    @Override
    public String toString() {
        return "DbToken[expiresAt=" + expiresAt + "]";
    }
}
