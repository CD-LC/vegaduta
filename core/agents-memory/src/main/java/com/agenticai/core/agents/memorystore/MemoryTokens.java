package com.agenticai.core.agents.memorystore;

/**
 * A conservative token estimate for recalled text: one token per four characters of Latin-range
 * text and one per character for scripts at U+0900 and above (Indic, Thai, CJK), which tokenise
 * densely. Mirrors the shape of the turn path's history estimator, which is package-private in
 * agents-turn and cannot be reached from here.
 */
final class MemoryTokens {

    private MemoryTokens() {}

    static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long dense = 0;
        long other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0x0900) {
                dense++;
            } else {
                other++;
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, dense + (other + 3) / 4);
    }

    /** Text cut to at most {@code maxTokens}, at a sentence boundary when one fits; null when nothing fits. */
    static String truncate(String text, int maxTokens) {
        if (estimate(text) <= maxTokens) {
            return text;
        }
        int end = text.length();
        while (end > 0 && estimate(text.substring(0, end)) > maxTokens) {
            end = Math.max(0, end - Math.max(1, end / 8));
        }
        if (end == 0) {
            return null;
        }
        String head = text.substring(0, end);
        int sentence = Math.max(Math.max(head.lastIndexOf(". "), head.lastIndexOf("! ")), head.lastIndexOf("? "));
        if (sentence > 0) {
            return head.substring(0, sentence + 1);
        }
        return null;
    }
}
