package com.agenticai.core.providers.modality;

/**
 * Base exception for video-to-text transcription errors.
 * Subclasses distinguish between input errors (bad video) and provider errors
 * (API down, rate limited, etc.).
 */
public abstract class VideoToTextException extends Exception {
    public VideoToTextException(String message) {
        super(message);
    }

    public VideoToTextException(String message, Throwable cause) {
        super(message, cause);
    }
}
