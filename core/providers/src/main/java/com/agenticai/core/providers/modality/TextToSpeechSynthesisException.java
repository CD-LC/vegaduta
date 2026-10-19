package com.agenticai.core.providers.modality;

/**
 * Thrown when TTS synthesis fails due to the input text itself (empty after
 * normalization, unsupported characters for the target voice, etc.) - a
 * client-side error, not a provider failure. Mirrors {@link
 * SpeechToTextTranscriptionException}'s shape.
 */
public class TextToSpeechSynthesisException extends TextToSpeechException {
    public TextToSpeechSynthesisException(String message) {
        super(message);
    }

    public TextToSpeechSynthesisException(String message, Throwable cause) {
        super(message, cause);
    }
}
