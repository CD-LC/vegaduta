package com.agenticai.core.admin.localauth;

/**
 * A refused local-auth request. Carries the HTTP status and a stable machine-readable code the web
 * client switches on; the message is safe to show to the person (it never says whether an account
 * exists). {@code retryAfterSeconds} is set for throttled attempts.
 */
public class LocalAuthException extends RuntimeException {

    private final int status;
    private final String code;
    private final Long retryAfterSeconds;

    public LocalAuthException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public LocalAuthException(int status, String code, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static LocalAuthException invalidCredentials() {
        return new LocalAuthException(401, "invalid_credentials", "The e-mail address or password is incorrect.");
    }

    public static LocalAuthException invalidSession() {
        return new LocalAuthException(401, "invalid_session", "Your session has ended. Please sign in again.");
    }

    public static LocalAuthException invalidSetupToken() {
        return new LocalAuthException(
                401, "invalid_setup_token", "This setup link is invalid or has expired. Restart the server to get a new one.");
    }

    public static LocalAuthException weakPassword(String message) {
        return new LocalAuthException(400, "weak_password", message);
    }

    public static LocalAuthException throttled(long retryAfterSeconds) {
        return new LocalAuthException(
                429, "too_many_attempts", "Too many failed attempts. Try again later.", retryAfterSeconds);
    }

    public static LocalAuthException weakPin(String message) {
        return new LocalAuthException(400, "weak_pin", message);
    }

    /** The access token is valid but its sign-in is too old for a sensitive change. */
    public static LocalAuthException reauthRequired() {
        return new LocalAuthException(403, "reauth_required", "Please sign in again to make this change.");
    }

    /** The device secret was right but the device was revoked (too many wrong PINs, or removed). */
    public static LocalAuthException deviceRevoked() {
        return new LocalAuthException(
                401, "device_revoked", "This device can no longer sign in with a PIN. Use another way to sign in.");
    }

    public static LocalAuthException passkeyInvalid() {
        return new LocalAuthException(400, "passkey_invalid", "The passkey could not be verified. Please try again.");
    }

    public static LocalAuthException passkeyOriginUnsupported(String message) {
        return new LocalAuthException(400, "passkey_origin_unsupported", message);
    }

    /** Removing this would leave the account with no way to sign in. */
    public static LocalAuthException lastCredential() {
        return new LocalAuthException(
                409,
                "last_credential",
                "This is the only way to sign in to this account. Add another one before removing it.");
    }

    public static LocalAuthException notFound() {
        return new LocalAuthException(404, "not_found", "That item does not exist.");
    }

    public static LocalAuthException limitReached(String message) {
        return new LocalAuthException(409, "limit_reached", message);
    }
}
