package com.agenticai.core.sarvam;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure language-code helpers for Sarvam's speech endpoints - no I/O, no Spring.
 *
 * <p>Two different lists matter and they are NOT the same size, which is the whole reason this
 * class exists (never promise a spoken reply in a language Sarvam cannot speak):
 * <ul>
 *   <li>speech-to-text ({@code saaras}) accepts 22 Indian languages plus English and can
 *   auto-detect which one was spoken (https://docs.sarvam.ai/api-reference-docs/getting-started/models/saaras);</li>
 *   <li>text-to-speech ({@code bulbul:v2}/{@code v3}) speaks only the eleven codes in
 *   {@link #TTS_LANGUAGES} (https://docs.sarvam.ai/api-reference-docs/text-to-speech/convert).</li>
 * </ul>
 * So a person can send a voice note in a language that is transcribed fine but has no voice to
 * answer with; callers must ask {@link #ttsLanguageFor} and fall back to a text reply.
 *
 * <p>Codes are Sarvam's BCP-47 style ({@code hi-IN}); note Odia is {@code od-IN} there, not the
 * ISO 639-1 {@code or}.
 */
public final class SarvamIndicLanguages {

    /** The eleven target_language_code values the text-to-speech endpoint documents. */
    public static final Set<String> TTS_LANGUAGES = Set.of(
        "bn-IN", "en-IN", "gu-IN", "hi-IN", "kn-IN", "ml-IN", "mr-IN", "od-IN", "pa-IN", "ta-IN", "te-IN"
    );

    private static final Map<String, String> ENGLISH_NAMES = Map.ofEntries(
        Map.entry("bn-IN", "Bengali"),
        Map.entry("en-IN", "English"),
        Map.entry("gu-IN", "Gujarati"),
        Map.entry("hi-IN", "Hindi"),
        Map.entry("kn-IN", "Kannada"),
        Map.entry("ml-IN", "Malayalam"),
        Map.entry("mr-IN", "Marathi"),
        Map.entry("od-IN", "Odia"),
        Map.entry("pa-IN", "Punjabi"),
        Map.entry("ta-IN", "Tamil"),
        Map.entry("te-IN", "Telugu")
    );

    private SarvamIndicLanguages() {
    }

    /**
     * Canonical {@code ll-RR} form of a language tag, or {@code null} when there is nothing usable
     * (null, blank, Sarvam's own {@code unknown}). Accepts {@code hi}, {@code hi_IN}, {@code HI-in},
     * and the ISO 639-1 Odia code {@code or}. A bare language subtag that Sarvam serves gets the
     * {@code -IN} region; any other tag is returned with its language lower-cased and region
     * upper-cased, unchanged otherwise (so {@code en-US} stays {@code en-US}).
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "unknown".equalsIgnoreCase(trimmed)) {
            return null;
        }
        String[] parts = trimmed.replace('_', '-').split("-");
        String language = parts[0].toLowerCase(Locale.ROOT);
        if (language.isEmpty() || language.length() > 3) {
            return null;
        }
        if ("or".equals(language)) {
            language = "od";
        }
        if (parts.length == 1) {
            return TTS_LANGUAGES.contains(language + "-IN") ? language + "-IN" : language;
        }
        return language + "-" + parts[1].toUpperCase(Locale.ROOT);
    }

    /**
     * The text-to-speech {@code target_language_code} to speak a reply in the given (detected)
     * language, or empty when Sarvam has no voice for it. Any English variant maps to {@code en-IN}
     * (the only English voice set Sarvam documents).
     */
    public static Optional<String> ttsLanguageFor(String detected) {
        String normalized = normalize(detected);
        if (normalized == null) {
            return Optional.empty();
        }
        String base = normalized.contains("-") ? normalized.substring(0, normalized.indexOf('-')) : normalized;
        String candidate = base + "-IN";
        return TTS_LANGUAGES.contains(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    /** English display name for a code we know ("Hindi"), else {@code null}. */
    public static String displayName(String code) {
        String normalized = normalize(code);
        if (normalized == null) {
            return null;
        }
        String direct = ENGLISH_NAMES.get(normalized);
        if (direct != null) {
            return direct;
        }
        String base = normalized.contains("-") ? normalized.substring(0, normalized.indexOf('-')) : normalized;
        return ENGLISH_NAMES.get(base + "-IN");
    }
}
