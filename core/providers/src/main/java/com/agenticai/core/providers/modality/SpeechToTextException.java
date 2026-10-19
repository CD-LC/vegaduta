package com.agenticai.core.providers.modality;

/**
 * Base exception for speech-to-text (STT) provider errors.
 * All {@link SpeechToTextProvider} implementations throw subclasses of this.
 */
public abstract class SpeechToTextException extends Exception {
    public SpeechToTextException(String message) {
        super(message);
    }

    public SpeechToTextException(String message, Throwable cause) {
        super(message, cause);
    }
}
