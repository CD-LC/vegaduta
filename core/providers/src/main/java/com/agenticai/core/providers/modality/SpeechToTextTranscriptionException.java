package com.agenticai.core.providers.modality;

/**
 * Thrown when audio transcription fails due to audio quality issues or
 * unsupported formats. This is a client-side error (bad audio, not clear
 * speech) rather than a provider-side error.
 *
 * <p>Mapped to HTTP 400 (Bad Request) with a user-friendly message asking
 * the user to try speaking more clearly or ensuring audio is clear.
 */
public class SpeechToTextTranscriptionException extends SpeechToTextException {
    public SpeechToTextTranscriptionException(String message) {
        super(message);
    }

    public SpeechToTextTranscriptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
