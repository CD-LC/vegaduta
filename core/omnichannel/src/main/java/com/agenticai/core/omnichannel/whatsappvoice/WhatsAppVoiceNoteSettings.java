package com.agenticai.core.omnichannel.whatsappvoice;

/**
 * The {@code app.omnichannel.whatsapp.voice-notes.*} knobs, as one immutable value so the service
 * can be built by hand in tests.
 *
 * @param maxSeconds           longest voice note accepted; clamped by {@link #effectiveMaxSeconds()}
 * @param maxBytes             largest media file downloaded (also the in-memory buffer bound)
 * @param replyMaxChars        a text reply longer than this is cut to a spoken summary before TTS
 * @param maxPerSenderPerHour  in-memory per-sender limiter, 0 = unlimited
 * @param maxPerTenantPerDay   DB-backed (Sarvam call log) cap on speech-to-text calls per tenant, 0 = unlimited
 * @param verifySha256         compare the downloaded bytes to the sha256 in the webhook
 */
public record WhatsAppVoiceNoteSettings(
    boolean enabled,
    boolean replyWithVoice,
    int maxSeconds,
    long maxBytes,
    int downloadTimeoutSeconds,
    int sttTimeoutSeconds,
    int ttsTimeoutSeconds,
    int replyMaxChars,
    int maxPerSenderPerHour,
    int maxPerTenantPerDay,
    boolean verifySha256,
    String sttModel,
    String sttMode,
    String ttsModel,
    String ttsSpeaker,
    String ttsCodec
) {

    /**
     * Sarvam's synchronous speech-to-text endpoint accepts at most 30 seconds of audio
     * (https://docs.sarvam.ai/api-reference-docs/getting-started/models/saaras - longer files need the
     * batch API, which this lane does not use). A configured value above this is clamped to it.
     */
    public static final int SARVAM_REST_MAX_SECONDS = 30;

    /** All flags off, conservative caps - what the service gets when nothing is configured. */
    public static WhatsAppVoiceNoteSettings defaults() {
        return new WhatsAppVoiceNoteSettings(
            false, false, 60, 1_048_576L, 10, 25, 25, 500, 20, 300, true, "", "codemix", "", "", "mp3");
    }

    public int effectiveMaxSeconds() {
        return Math.max(1, Math.min(maxSeconds, SARVAM_REST_MAX_SECONDS));
    }

    public WhatsAppVoiceNoteSettings withReplyWithVoice(boolean value) {
        return new WhatsAppVoiceNoteSettings(enabled, value, maxSeconds, maxBytes, downloadTimeoutSeconds,
            sttTimeoutSeconds, ttsTimeoutSeconds, replyMaxChars, maxPerSenderPerHour, maxPerTenantPerDay,
            verifySha256, sttModel, sttMode, ttsModel, ttsSpeaker, ttsCodec);
    }

    public WhatsAppVoiceNoteSettings withEnabled(boolean value) {
        return new WhatsAppVoiceNoteSettings(value, replyWithVoice, maxSeconds, maxBytes, downloadTimeoutSeconds,
            sttTimeoutSeconds, ttsTimeoutSeconds, replyMaxChars, maxPerSenderPerHour, maxPerTenantPerDay,
            verifySha256, sttModel, sttMode, ttsModel, ttsSpeaker, ttsCodec);
    }
}
