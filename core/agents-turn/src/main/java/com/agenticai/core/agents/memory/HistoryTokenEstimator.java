package com.agenticai.core.agents.memory;

/**
 * Cheap, dependency-free token estimator used to bound the chat-history replay window. Extracted
 * unchanged from {@link SessionMemoryService}, which keeps a delegating public method.
 */
final class HistoryTokenEstimator {

    private HistoryTokenEstimator() {}

    /**
     * Cheap, dependency-free token ESTIMATE for one turn's content - used only to bound the replay
     * window, never for billing.
     *
     * <p>Why this exists: the window used to be bounded by characters alone ({@code
     * history-max-chars}, 32k by default), on the assumption of ~4 characters per token, i.e. ~8k
     * tokens of replay. That ratio is a Latin-script property. BPE tokenizers (cl100k/o200k,
     * Gemini's SentencePiece, Claude's) spend roughly one token per character on CJK ideographs and
     * kana/hangul, and between one and three per character on Devanagari and the other Indic
     * scripts, so a Hindi or Japanese session under the same 32k-char cap could replay 30k+ tokens
     * - a 4x cost and context blow-up for exactly the users this platform most wants to serve.
     *
     * <p>The estimate: every code point in a "dense" script (CJK Unified Ideographs and extensions,
     * Hiragana/Katakana, Hangul, CJK symbols and full-width forms, Devanagari and the other Indic
     * blocks U+0900-U+0DFF, Thai) counts as one token; everything else is pooled and counted at one
     * token per four characters, rounded up. One-per-char is a deliberate floor rather than a mean
     * for Indic text - the goal is a cap that is never wildly optimistic, not a tokenizer; the
     * separate char cap still applies on top. No tokenizer library: this sits on the chat hot path,
     * runs for up to {@code history-max-turns} turns per message, and must not depend on which
     * provider (with which vocabulary) serves the turn.
     */
    static long estimateTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        long dense = 0;
        long other = 0;
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            i += Character.charCount(cp);
            if (isDenseScript(cp)) {
                dense++;
            } else {
                other++;
            }
        }
        return dense + (other + 3) / 4;
    }

    private static boolean isDenseScript(int cp) {
        if (cp < 0x0900) {
            return false; // fast path: ASCII, Latin, Greek, Cyrillic, Arabic, Hebrew...
        }
        if (cp <= 0x0DFF) {
            return true; // Devanagari, Bengali, Gurmukhi, Gujarati, Oriya, Tamil, Telugu, Kannada,
            // Malayalam, Sinhala
        }
        if (cp >= 0x0E00 && cp <= 0x0E7F) {
            return true; // Thai
        }
        Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(cp);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return switch (script) {
            case HAN, HIRAGANA, KATAKANA, HANGUL, BOPOMOFO -> true;
            default ->
                    (cp >= 0x3000 && cp <= 0x303F)
                            || (cp >= 0xFF00 && cp <= 0xFFEF); // CJK punctuation, full-width forms
        };
    }
}
