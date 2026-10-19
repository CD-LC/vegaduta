package com.agenticai.core.elevenlabs.agenttools;

/**
 * Thrown by {@link MusicGenerationQuotaService} when a platform-paid music generation would exceed
 * the tenant's tier cap. The message is already written for a person; {@link
 * GenerateMusicToolCallback} relays it to the model, which repeats it in the user's own language.
 */
public class MusicGenerationQuotaExceededException extends RuntimeException {
    public MusicGenerationQuotaExceededException(String message) {
        super(message);
    }
}
