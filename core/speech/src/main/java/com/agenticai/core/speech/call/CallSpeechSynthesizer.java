package com.agenticai.core.speech.call;

import java.util.List;

/**
 * One text-to-speech provider for live voice calls ({@code voice.VoiceCallController}'s reply
 * audio). Kernel-owned (ADR-0011 rule a): implemented in the {@code elevenlabs} and {@code sarvam}
 * modules; the controller keeps the fallback order and picks providers by {@link #method()}.
 */
public interface CallSpeechSynthesizer {

    /** The label the call API reports for audio from this provider, e.g. {@code "elevenlabs"}. */
    String method();

    /** The voice used when the caller names none, e.g. the tenant's newest cloned voice. */
    String defaultVoice();

    /**
     * @return the audio, or {@code null}/empty when the provider produced none
     * @throws CallSpeechQuotaExceededException when the tenant's voice quota is used up - never
     *     swallowed, callers must tell it apart from "provider unavailable"
     * @throws RuntimeException any other provider failure; callers log it and fall through
     */
    byte[] synthesize(String text, String voice);

    /**
     * Persists one finished utterance in this provider's audio ledger (which the voice quota
     * counts).
     */
    void store(byte[] audio, String voice);

    /** Joins clips in this provider's audio format into one playable file. */
    byte[] concatenate(List<byte[]> clips);
}
