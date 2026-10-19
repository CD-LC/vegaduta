package com.agenticai.core.chat;

import java.util.Locale;

/**
 * Phase P4 (Spend &amp; Reports): "add per-modality where derivable"
 * (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §3/§4). {@code llm_call_log}
 * (V52) carries a model id but no explicit modality column, so modality is
 * derived here from the model id by prefix/substring match - an honest
 * heuristic, deliberately conservative: anything not recognized as image or
 * audio falls back to {@link #TEXT} (the overwhelming majority of rows, since
 * {@code llm_call_log} is written by the chat-completion advisor), never an
 * invented category.
 *
 * <p>Pure, stateless, no Spring - used both by {@code LlmUsageRollupService}
 * when pre-aggregating and directly unit-testable without a context. The four
 * buckets mirror the modality vocabulary the governance doc uses (text /
 * image / voice+audio / other), with "video = pay-extra" (§4) intentionally
 * NOT a bucket here: video generation is billed out-of-band and never lands
 * on {@code llm_call_log} in the first place.
 */
public final class ModalityClassifier {

    public static final String TEXT = "text";
    public static final String IMAGE = "image";
    public static final String AUDIO = "audio";
    public static final String OTHER = "other";

    private ModalityClassifier() {
    }

    /**
     * @param model the model id recorded on the call (may be {@code null} in
     *     malformed/error rows).
     * @return one of {@link #TEXT}, {@link #IMAGE}, {@link #AUDIO}, {@link #OTHER}.
     */
    public static String classify(String model) {
        if (model == null || model.isBlank()) {
            return OTHER;
        }
        String m = model.toLowerCase(Locale.ROOT);

        // Image generation / vision-gen families.
        if (m.contains("dall-e")
            || m.contains("dalle")
            || m.contains("imagen")
            || m.contains("stable-diffusion")
            || m.contains("gpt-image")
            || m.contains("flux")
            || m.contains("-image")
            || m.contains("image-")) {
            return IMAGE;
        }

        // Speech / audio families: STT (transcription), TTS (speech), and the
        // Sarvam/ElevenLabs voice stack this platform integrates.
        if (m.contains("whisper")
            || m.contains("tts")
            || m.contains("-audio")
            || m.contains("audio-")
            || m.contains("speech")
            || m.contains("voice")
            || m.contains("eleven")
            || m.contains("sarvam")
            || m.contains("saarika")
            || m.contains("bulbul")) {
            return AUDIO;
        }

        // Everything else is a text/chat completion - the common case.
        return TEXT;
    }
}
