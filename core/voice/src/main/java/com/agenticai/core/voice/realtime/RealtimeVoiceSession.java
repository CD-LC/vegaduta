package com.agenticai.core.voice.realtime;

/**
 * One open bidirectional session with a native speech-to-speech provider.
 * Implementations own the upstream WebSocket and translate provider wire
 * frames into {@link RealtimeVoiceListener} events.
 *
 * <p><b>Audio contract:</b> input is signed 16-bit little-endian PCM at the
 * provider's input rate (16 kHz for Gemini Live, 24 kHz for OpenAI
 * Realtime); output arrives via {@link
 * RealtimeVoiceListener#onAudioChunk} at the provider's output rate (24 kHz
 * both). The relay handler documents both rates to the client in its setup
 * reply so resampling, when needed, happens client-side - this backend
 * deliberately stays a dumb pipe between the two sockets.
 *
 * <p>All methods must be safe to call from any thread; both providers'
 * {@code java.net.http.WebSocket} sends are thread-confined by the JDK
 * client internally.
 */
public interface RealtimeVoiceSession extends AutoCloseable {

    /** Appends one chunk of caller audio to the provider's input buffer. */
    void sendAudio(byte[] pcm16le);

    /**
     * Barge-in: the caller started speaking while the agent was still
     * talking. Cancels the in-flight response server-side where the
     * provider supports it (OpenAI {@code response.cancel}); for providers
     * that auto-handle interruption (Gemini Live's VAD) this is a no-op.
     */
    void interrupt();

    /**
     * Ends the upstream session. Idempotent; the listener receives no more
     * events after this returns.
     */
    @Override
    void close();
}
