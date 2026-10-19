package com.agenticai.core.speech.call;

/**
 * The speech-to-text fallback for live voice calls, tried after the agent's own provider and the
 * sidecar. Kernel-owned (ADR-0011 rule a); implemented in the {@code sarvam} module.
 */
public interface CallSpeechTranscriber {

    /**
     * @return the transcript (may be blank)
     * @throws NotConfiguredException when the tenant has no credential for this provider - a
     *     configuration gap, distinct from a transcription failure
     * @throws RuntimeException any other failure
     */
    String transcribe(byte[] audio, String filename);

    class NotConfiguredException extends RuntimeException {
        public NotConfiguredException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
