package com.agenticai.core.omnichannel.whatsappvoice;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns the text reply of an agent into what is worth SPEAKING as a WhatsApp voice note: code and
 * URLs are dropped (never read out), markdown is flattened, and a long reply is cut to a spoken
 * summary length at a sentence boundary. The full reply still goes out as text in the same chat, so
 * nothing is lost - the voice note is a convenience, not the record.
 *
 * <p>Pure and deterministic. Sentence ends include the Devanagari danda (U+0964) and double danda
 * (U+0965) as well as {@code . ! ?}, so Hindi/Marathi replies are cut at a real sentence boundary.
 * Nothing here touches non-ASCII letters, so Indic scripts pass through unchanged.
 */
final class SpokenReplyShortener {

    private static final Pattern FENCED_CODE = Pattern.compile("```.*?(```|$)", Pattern.DOTALL);
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]\\n]*)\\]\\((?:[^)\\s]*)\\)");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern HTML_TAG = Pattern.compile("</?[A-Za-z][^>\\n]{0,200}>");
    private static final Pattern LEADING_MARKUP = Pattern.compile("(?m)^[ \\t]*(?:#{1,6}|>|[-*+]|\\d{1,3}[.)])[ \\t]+");
    private static final Pattern EMPHASIS = Pattern.compile("[*_~]{1,3}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SpokenReplyShortener() {
    }

    /**
     * @param reply    the full text reply
     * @param maxChars upper bound on the spoken text length (values below 20 are raised to 20)
     * @return the text to speak, or empty when nothing speakable remains (empty reply, or all code/URLs)
     */
    static Optional<String> shorten(String reply, int maxChars) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        int cap = Math.max(20, maxChars);
        String text = FENCED_CODE.matcher(reply).replaceAll(" ");
        text = INLINE_CODE.matcher(text).replaceAll(" ");
        text = MD_LINK.matcher(text).replaceAll("$1");
        text = URL.matcher(text).replaceAll(" ");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        text = LEADING_MARKUP.matcher(text).replaceAll("");
        text = EMPHASIS.matcher(text).replaceAll("");
        text = WHITESPACE.matcher(text).replaceAll(" ").trim();
        if (!containsLetterOrDigit(text)) {
            return Optional.empty();
        }
        if (text.length() <= cap) {
            return Optional.of(text);
        }
        return Optional.of(cutAtBoundary(text, cap));
    }

    private static boolean containsLetterOrDigit(String s) {
        int letters = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                letters++;
                if (letters >= 1) {
                    return true;
                }
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static String cutAtBoundary(String text, int cap) {
        int limit = cap;
        if (Character.isHighSurrogate(text.charAt(limit - 1))) {
            limit--;
        }
        String head = text.substring(0, limit);
        int best = -1;
        for (int i = head.length() - 1; i >= head.length() / 3; i--) {
            char c = head.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '।' || c == '॥') {
                best = i + 1;
                break;
            }
        }
        if (best < 0) {
            int space = head.lastIndexOf(' ');
            best = space >= head.length() / 3 ? space : head.length();
        }
        return head.substring(0, best).trim();
    }
}
