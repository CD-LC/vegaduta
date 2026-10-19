package com.agenticai.core.providers.modality;

/**
 * Base exception for text-to-speech (TTS) provider errors. All {@link
 * TextToSpeechProvider} implementations throw subclasses of this - mirrors
 * {@link SpeechToTextException}'s shape.
 */
public abstract class TextToSpeechException extends Exception {
    public TextToSpeechException(String message) {
        super(message);
    }

    public TextToSpeechException(String message, Throwable cause) {
        super(message, cause);
    }
}
