package com.agenticai.core.providers.modality;

/**
 * Thrown when a text-to-speech provider is unavailable or fails to respond -
 * a server-side error (provider unreachable, API error, rate limited,
 * invalid credentials), not a client input issue. Mirrors {@link
 * SpeechToTextProviderException}'s shape.
 */
public class TextToSpeechProviderException extends TextToSpeechException {
    private final int httpStatusCode;
    private final boolean retryable;

    public TextToSpeechProviderException(String message, int httpStatusCode, boolean retryable) {
        super(message);
        this.httpStatusCode = httpStatusCode;
        this.retryable = retryable;
    }

    public TextToSpeechProviderException(String message, Throwable cause, boolean retryable) {
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
