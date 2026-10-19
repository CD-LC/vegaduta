package com.agenticai.core.voice;

/**
 * Thrown by {@link LiveKitEgressService} when a call to LiveKit's Egress
 * (Twirp) API fails - unreachable server, a non-2xx response, or a
 * malformed response body. Distinct from {@link LiveKitNotConfiguredException}
 * (missing API key/secret, a config problem) and from the recording being
 * merely disabled (handled in {@link VoiceCallController} via {@code
 * ResponseStatusException} so it doesn't need a new {@code
 * GlobalExceptionHandler} case) - this one is a genuine runtime/infra
 * failure talking to LiveKit itself, so it's left unmapped and surfaces as a
 * 500, same as any other unexpected integration failure in this codebase.
 */
public class LiveKitEgressException extends RuntimeException {

    public LiveKitEgressException(String message) {
        super(message);
    }

    public LiveKitEgressException(String message, Throwable cause) {
        super(message, cause);
    }
}
