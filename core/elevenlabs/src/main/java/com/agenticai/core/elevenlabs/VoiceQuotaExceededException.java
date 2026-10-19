package com.agenticai.core.elevenlabs;

/**
 * Visitor-facing reason a text-to-speech request was rejected before any
 * ElevenLabs API call was made - never a raw provider error. Mirrors {@code
 * VideoGenerationQuotaExceededException} in {@code core.imagegen} exactly.
 */
public class VoiceQuotaExceededException extends RuntimeException {

    public VoiceQuotaExceededException(String message) {
        super(message);
    }
}
