package com.agenticai.core.speech.voicenote;

/**
 * A {@link VoiceSpeechGateway} call was refused or failed upstream. The message must never carry
 * audio, a transcript or a phone number (it can reach logs); provider error text is not propagated
 * either.
 */
public class VoiceSpeechException extends RuntimeException {

    /** What went wrong, provider-neutral. */
    public enum Failure {
        /** The current tenant has no active credential for the provider. */
        NOT_CONFIGURED,
        /**
         * The provider answered with an error (the message carries at most the upstream HTTP
         * status).
         */
        UPSTREAM_FAILED
    }

    private final Failure failure;

    public VoiceSpeechException(Failure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public VoiceSpeechException(Failure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public Failure getFailure() {
        return failure;
    }
}
