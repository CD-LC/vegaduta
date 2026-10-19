package com.agenticai.core.providers.modality;

/**
 * Thrown when the speech-to-text provider is unavailable or fails to respond.
 * This is a server-side error (provider unreachable, API error, rate limited,
 * invalid credentials) rather than a client audio-quality issue.
 *
 * <p>Mapped to HTTP 503 (Service Unavailable) or 402 (Payment Required) when
 * appropriate, with a message asking the user to try again or contact support.
 * Fallback providers can be tried before giving up.
 */
public class SpeechToTextProviderException extends SpeechToTextException {
    private final int httpStatusCode;
    private final boolean retryable;

    /**
     * @param message        human-readable error message
     * @param httpStatusCode the HTTP status code from the provider (e.g., 503, 429)
     *                       or 0 if unknown
     * @param retryable      true if the caller should try another provider or
     *                       retry after a delay; false if the error is permanent
     *                       (invalid credentials, etc.)
     */
    public SpeechToTextProviderException(String message, int httpStatusCode, boolean retryable) {
        super(message);
        this.httpStatusCode = httpStatusCode;
        this.retryable = retryable;
    }

    /**
     * @param message human-readable error message
     * @param cause   the underlying exception
     * @param retryable true if the caller should try another provider
     */
    public SpeechToTextProviderException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.httpStatusCode = 0;
        this.retryable = retryable;
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
