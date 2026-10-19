package com.agenticai.core.tenancy.account;

/**
 * Thrown by {@link AccountSelfDeletionService} when the caller's last interactive
 * sign-in (JWT {@code auth_time}) is missing or older than
 * {@code app.account.deletion.max-auth-age}. Mapped to HTTP 401 with the stable
 * machine-readable code {@value #CODE} and a {@code max_age} hint (seconds) by
 * {@code GlobalExceptionHandler}. Clients re-authenticate at the identity provider
 * with {@code prompt=login&max_age=0} and retry the request.
 */
public class ReauthRequiredException extends RuntimeException {

    public static final String CODE = "reauth_required";

    private final long maxAgeSeconds;

    public ReauthRequiredException(long maxAgeSeconds) {
        super("Please sign in again to confirm deleting your account. Nothing was deleted.");
        this.maxAgeSeconds = maxAgeSeconds;
    }

    /** The maximum acceptable age of the sign-in, in seconds (the {@code max_age} hint). */
    public long getMaxAgeSeconds() {
        return maxAgeSeconds;
    }
}
