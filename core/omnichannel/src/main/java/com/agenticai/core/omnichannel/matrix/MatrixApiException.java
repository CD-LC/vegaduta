package com.agenticai.core.omnichannel.matrix;

/**
 * A failed call to a Matrix homeserver. The message is built from the homeserver's {@code errcode}
 * and {@code error} text only: never the access token, never the request URL.
 */
public class MatrixApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** HTTP status, or -1 when no response arrived (network failure, refused destination). */
    private final int status;

    private final String errcode;
    private final long retryAfterMs;

    public MatrixApiException(int status, String errcode, String message) {
        this(status, errcode, message, 0L, null);
    }

    public MatrixApiException(
            int status, String errcode, String message, long retryAfterMs, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errcode = errcode == null ? "M_UNKNOWN" : errcode;
        this.retryAfterMs = retryAfterMs;
    }

    public int status() {
        return status;
    }

    public String errcode() {
        return errcode;
    }

    /** The homeserver's requested wait after {@code M_LIMIT_EXCEEDED}, or 0. */
    public long retryAfterMs() {
        return retryAfterMs;
    }

    /** The access token is dead (revoked, logged out, expired): retrying cannot help. */
    public boolean isUnknownToken() {
        return "M_UNKNOWN_TOKEN".equals(errcode) || "M_MISSING_TOKEN".equals(errcode);
    }

    public boolean isRateLimited() {
        return status == 429 || "M_LIMIT_EXCEEDED".equals(errcode);
    }

    /** A 4xx other than a rate limit: the request itself is wrong, a retry would repeat it. */
    public boolean isPermanentClientError() {
        return status >= 400 && status < 500 && !isRateLimited();
    }
}
