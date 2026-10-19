package com.agenticai.core.elevenlabs;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * Thrown by {@link ElevenLabsCredentialService#resolveActiveApiKey()} when no tenant credential is
 * active yet - mirrors {@code SarvamNotConfiguredException}. Also reused by {@link
 * ElevenLabsVoiceService#mostRecent()} (via the custom-message constructor) for the sibling "no
 * voice cloned yet" case - same "not configured, 400, tell the caller what setup step to do" shape
 * either way.
 */
public class ElevenLabsNotConfiguredException extends RuntimeException implements HttpStatusMapped {

    public ElevenLabsNotConfiguredException() {
        super(
                "No active ElevenLabs credential configured - POST /api/elevenlabs/credential first.");
    }

    public ElevenLabsNotConfiguredException(String message) {
        super(message);
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
