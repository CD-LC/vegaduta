package com.agenticai.core.elevenlabs.agenttools;

/**
 * No ElevenLabs key - neither the tenant's own nor the platform's - can serve a music generation
 * right now.
 */
public class MusicGenNotConfiguredException extends RuntimeException {
    public MusicGenNotConfiguredException(String message) {
        super(message);
    }
}
