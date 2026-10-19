package com.agenticai.core.voice.realtime;

/**
 * Events from a {@link RealtimeVoiceSession}, delivered on the provider
 * WebSocket's delivery thread. Implementations of the relay forward these
 * to the downstream client socket; they must therefore be cheap (hand off,
 * don't block) - {@code java.net.http.WebSocket} pauses upstream delivery
 * while a callback is running.
 */
public interface RealtimeVoiceListener {

    /** One chunk of agent speech audio (PCM16 LE at the provider output rate). */
    default void onAudioChunk(byte[] pcm16le) {}

    /** Transcript of what the CALLER said, when the provider emits one. */
    default void onInputTranscript(String text) {}

    /** Transcript of what the AGENT is saying, when the provider emits one. */
    default void onOutputTranscript(String text) {}

    /** The provider interrupted its own response (server-side VAD barge-in). */
    default void onInterrupted() {}

    /** Terminal provider error; the session is dead after this. */
    default void onError(String message) {}

    /** The upstream socket closed (cleanly or not) after {@code open} completed. */
    default void onClose() {}
}
