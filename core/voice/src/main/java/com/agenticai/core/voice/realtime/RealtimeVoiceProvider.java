package com.agenticai.core.voice.realtime;

/**
 * SPI for a native speech-to-speech provider - the realtime twin of the
 * {@code SpeechToTextProvider}/{@code TextToSpeechProvider} pair in
 * {@code core/providers/modality}. Each implementation wraps one provider's
 * bidirectional WebSocket API.
 */
public interface RealtimeVoiceProvider {

    /** Which {@link RealtimeProvider} this implementation serves. */
    RealtimeProvider getProvider();

    /**
     * Opens the upstream session and returns once the provider has
     * acknowledged setup (so failures surface to the session-create caller,
     * not later mid-audio). Implementations translate provider frames into
     * {@code listener} events from that point on.
     *
     * @throws RealtimeVoiceException if the provider can't be reached or
     *         rejects the handshake (bad key, unknown model)
     */
    RealtimeVoiceSession open(RealtimeSessionConfig config, RealtimeVoiceListener listener);
}
