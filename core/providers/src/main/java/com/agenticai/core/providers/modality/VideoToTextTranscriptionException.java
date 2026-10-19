package com.agenticai.core.providers.modality;

/**
 * Thrown when video transcription fails due to an issue with the input video
 * itself (unsupported format, corrupted file, no audio track, etc.), not a
 * provider/deployment issue. Callers should treat this as non-recoverable
 * from a fallback perspective - another provider will likely have the same issue.
 */
public final class VideoToTextTranscriptionException extends VideoToTextException {
    public VideoToTextTranscriptionException(String message) {
        super(message);
    }

    public VideoToTextTranscriptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
