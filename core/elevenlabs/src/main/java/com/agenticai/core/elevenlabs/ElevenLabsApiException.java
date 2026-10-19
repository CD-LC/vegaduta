package com.agenticai.core.elevenlabs;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * Any failed call to ElevenLabs' REST API (voice clone, text-to-speech, voice delete) - carries the
 * real upstream HTTP status rather than a generic message, same "translate the upstream error,
 * don't swallow it" discipline {@code SarvamApiException}/{@code UpstreamProviderException} already
 * apply to every other provider integrated in this codebase.
 */
public class ElevenLabsApiException extends RuntimeException implements HttpStatusMapped {

    private final int upstreamStatus;

    public ElevenLabsApiException(int upstreamStatus, String message) {
        super(message);
        this.upstreamStatus = upstreamStatus;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }

    /**
     * 502: an outbound call to the third-party API failed. Answered by the central exception
     * handler.
     */
    @Override
    public int httpStatus() {
        return 502;
    }

    @Override
    public boolean logAtWarn() {
        return true;
    }
}
