package com.agenticai.core.providers.modality;

/**
 * Thrown when video transcription fails due to a provider-level error
 * (API unreachable, rate limited, credentials invalid, etc.), not an issue
 * with the input video itself. Callers should catch this and attempt fallback
 * to the next provider in the chain.
 *
 * <p>Common examples:
 * <ul>
 *   <li>OpenAI API returns 429 (rate limited) or 500 (server error)</li>
 *   <li>Sidecar Whisper is down or unreachable</li>
 *   <li>Sarvam credential is missing or expired</li>
 * </ul>
 */
public final class VideoToTextProviderException extends VideoToTextException {
    public VideoToTextProviderException(String message) {
        super(message);
    }

    public VideoToTextProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
