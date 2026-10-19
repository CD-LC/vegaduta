package com.agenticai.core.agents.chatport;

/**
 * Small pure text helpers shared by the chat import parser and the export renderer. Imported transcripts are
 * untrusted: they can carry control characters, bidi overrides and the Unicode TAG block (used to smuggle hidden
 * instructions), none of which a stored or exported chat needs.
 */
final class ChatPortText {

    private static final String ELLIPSIS = "…";

    private ChatPortText() {
    }

    /**
     * Drops control characters (except newline and tab; CR and CRLF become newline), the TAG block, bidi controls,
     * zero-width spaces/marks, BOM and unpaired surrogates. Keeps ZWJ/ZWNJ (emoji and Indic scripts need them).
     */
    static String clean(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        int n = s.length();
        for (int i = 0; i < n; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\r') {
                if (i < n && s.charAt(i) == '\n') {
                    i++;
                }
                sb.append('\n');
                continue;
            }
            if (cp == '\n' || cp == '\t') {
                sb.append((char) cp);
                continue;
            }
            if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F)) {
                continue;
            }
            if (cp >= 0xE0000 && cp <= 0xE007F) {
                continue;
            }
            if (cp == 0x200B || cp == 0x200E || cp == 0x200F || cp == 0xFEFF || cp == 0x180E
                || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069)) {
                continue;
            }
            if (Character.getType(cp) == Character.SURROGATE || Character.getType(cp) == Character.PRIVATE_USE) {
                continue;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    /** Collapses every whitespace run (newlines included) to one space and trims. */
    static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = true;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (!space) {
                    sb.append(' ');
                    space = true;
                }
            } else {
                sb.appendCodePoint(cp);
                space = false;
            }
        }
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == ' ') {
            sb.setLength(len - 1);
        }
        return sb.toString();
    }

    /** At most {@code max} characters, cut on a code point boundary; {@code ellipsis} marks a cut. */
    static String truncate(String s, int max, boolean ellipsis) {
        if (s == null) {
            return "";
        }
        int limit = Math.max(0, max);
        if (s.length() <= limit) {
            return s;
        }
        int end = limit;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        String cut = s.substring(0, end);
        return ellipsis ? cut + ELLIPSIS : cut;
    }
}
