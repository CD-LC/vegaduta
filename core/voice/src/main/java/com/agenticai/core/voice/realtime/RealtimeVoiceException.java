package com.agenticai.core.voice.realtime;

/**
 * Provider-level failure opening or running a realtime voice session -
 * unreachable endpoint, rejected handshake, malformed upstream frame the
 * implementation can't recover from. The session-create controller maps
 * this to a 502-style upstream error; the relay handler maps it to a
 * close frame.
 */
public class RealtimeVoiceException extends RuntimeException {

    public RealtimeVoiceException(String message) {
        super(message);
    }

    public RealtimeVoiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
