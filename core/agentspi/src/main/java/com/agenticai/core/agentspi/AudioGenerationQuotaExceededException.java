package com.agenticai.core.agentspi;

/**
 * Thrown by the platform-voice quota services ({@code
 * sarvam.agenttools.AudioGenerationQuotaService}, {@code
 * elevenlabs.agenttools.ElevenLabsGenerationQuotaService}) when a platform-subsidised voice/audio
 * generation would exceed the tenant's tier cap. The message is already written for a person;
 * {@code tools.audio.SpeakToolCallback} relays it to the model, which repeats it in the user's own
 * language. Kernel-owned so both vendors can throw it without depending on each other (ADR-0011
 * rule b).
 */
public class AudioGenerationQuotaExceededException extends RuntimeException {
    public AudioGenerationQuotaExceededException(String message) {
        super(message);
    }
}
