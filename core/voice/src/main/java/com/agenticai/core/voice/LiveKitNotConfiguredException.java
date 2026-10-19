package com.agenticai.core.voice;

/**
 * Thrown by {@link LiveKitTokenService#issueToken} when {@code LIVEKIT_API_KEY}/
 * {@code LIVEKIT_API_SECRET} are unset - mirrors {@code SarvamNotConfiguredException}/
 * {@code ElevenLabsNotConfiguredException}'s "not configured yet, 400, tell the
 * caller what to do" shape (see {@code GlobalExceptionHandler}).
 */
public class LiveKitNotConfiguredException extends RuntimeException {

    public LiveKitNotConfiguredException(String message) {
        super(message);
    }
}
