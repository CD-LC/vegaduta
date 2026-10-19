package com.agenticai.core.agentspi;

/**
 * Composes a music clip with one named model and an explicit API key (the public demo's
 * platform-paid music runs). Kernel-owned (ADR-0011 rule a); implemented by {@code
 * elevenlabs.agenttools.ElevenLabsMusicComposer}.
 */
public interface MusicComposer {

    /**
     * @return the clip as MP3 bytes
     * @throws ComposeFailedException when the provider rejects the call - its upstream status lets
     *     the caller tell a model/request-shaped 4xx (worth trying the next model) from anything
     *     else
     */
    byte[] compose(
            String prompt, int lengthMs, String modelId, boolean forceInstrumental, String apiKey);

    class ComposeFailedException extends RuntimeException {

        private final int upstreamStatus;

        public ComposeFailedException(int upstreamStatus, String message, Throwable cause) {
            super(message, cause);
            this.upstreamStatus = upstreamStatus;
        }

        public int getUpstreamStatus() {
            return upstreamStatus;
        }
    }
}
