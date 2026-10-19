package com.agenticai.core.speech.voicenote;

import java.time.Instant;
import java.util.Optional;

/**
 * The speech half of a WhatsApp voice-note turn: speech-to-text, text-to-speech and the provider's
 * language table, behind a kernel-owned interface (ADR-0011 rule a) so the omnichannel voice-note
 * service never imports a speech vendor. It lives in the {@code speech} module because both sides
 * can see it there: omnichannel consumes it, and a vendor module (today {@code sarvam}, via {@code
 * SarvamVoiceSpeechGateway}) implements it. Tests use a fake.
 *
 * <p>Implementations raise {@link VoiceSpeechException} for a refusal with a meaning the caller
 * acts on (no credential, upstream failure) and any other RuntimeException for anything else; the
 * caller turns both into one of its outcomes and never lets either reach the webhook thread.
 */
public interface VoiceSpeechGateway {

    /** True when the current tenant has an active credential for this provider (its own key). */
    boolean isConfigured();

    /**
     * Speech-to-text calls the current tenant made since {@code since} (from the provider's call
     * log).
     */
    long speechToTextCallsSince(Instant since);

    /**
     * Transcribes with language auto-detect; {@code model}/{@code mode} blank = the provider
     * defaults.
     */
    Transcript transcribe(byte[] audio, String filename, String model, String mode);

    /**
     * Synthesizes {@code text}; returns the raw audio bytes as the provider sent them (container
     * NOT guaranteed - sniff it).
     */
    byte[] synthesize(String text, String languageCode, String model, String speaker, String codec);

    /**
     * The provider's canonical BCP-47 form of a detected language code ({@code hi} -> {@code
     * hi-IN}), or null.
     */
    String normalizeLanguage(String languageCode);

    /**
     * The English name of a language code ({@code hi-IN} -> {@code Hindi}), or null when unknown.
     */
    String languageDisplayName(String languageCode);

    /**
     * The text-to-speech language to answer a detected language in, or empty when the provider has
     * no voice for it.
     */
    Optional<String> ttsLanguageFor(String detectedLanguageCode);

    /**
     * @param languageCode the provider's detected BCP-47 code ({@code hi-IN}), null when it gave
     *     none
     */
    record Transcript(String text, String languageCode) {}
}
