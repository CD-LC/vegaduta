package com.agenticai.core.omnichannel.whatsappvoice;

/**
 * Every way a WhatsApp voice-note turn can end, one per metrics counter value
 * ({@code whatsapp.voice_note{outcome=...}}), so the counters say honestly what happened instead of
 * lumping every failure under "error".
 */
public enum VoiceNoteOutcome {
    /** Speech-to-text produced a transcript; the turn continues as a normal text turn. */
    TRANSCRIBED(false),
    RATE_LIMITED(true),
    /** The tenant has no active Sarvam credential, or hit its daily voice-note cap. */
    NOT_AVAILABLE(true),
    INVALID_AUDIO_REF(true),
    TOO_LONG(true),
    TOO_LARGE(true),
    UNSUPPORTED_MEDIA(true),
    DOWNLOAD_FAILED(true),
    INTEGRITY_FAILED(true),
    STT_FAILED(true),
    STT_TIMEOUT(true),
    EMPTY_TRANSCRIPT(true),
    OVERLOADED(true),
    VOICE_REPLY_SENT(false),
    VOICE_REPLY_NO_LANGUAGE(false),
    VOICE_REPLY_NOTHING_SPEAKABLE(false),
    VOICE_REPLY_TTS_FAILED(false),
    VOICE_REPLY_TTS_TIMEOUT(false),
    /** Sarvam returned audio WhatsApp cannot accept (WAV) and this build does not transcode. */
    VOICE_REPLY_FORMAT_UNSUPPORTED(false),
    VOICE_REPLY_SEND_FAILED(false);

    private final boolean inboundFailure;

    VoiceNoteOutcome(boolean inboundFailure) {
        this.inboundFailure = inboundFailure;
    }

    /** True when the inbound voice note could not be turned into a transcript. */
    public boolean isInboundFailure() {
        return inboundFailure;
    }
}
