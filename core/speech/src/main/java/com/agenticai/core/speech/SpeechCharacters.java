package com.agenticai.core.speech;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code-point level rules behind {@link SpeechTextNormalizer}: HTML entity decoding, emoji and
 * decorative-symbol removal, smart-punctuation folding, control/ANSI/invisible-character stripping
 * and whitespace collapsing. All code-point based (no {@code \w}), so they are inert on Indic and
 * other non-Latin scripts - see {@link #isSpeechNoiseSymbol}.
 */
final class SpeechCharacters {

    private static final char ESC = (char) 0x1B;
    private static final char BEL = (char) 0x07;
    private static final char DEL = (char) 0x7F;

    /** A Unicode code point is at most 6 hex / 7 decimal digits, so 8 is already generous. */
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]{1,8}|[0-9]{1,8});");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t\\u00A0\\u2007\\u202F]+");

    private SpeechCharacters() {
    }

    static String collapseSpaces(String s) {
        return MULTI_SPACE.matcher(s).replaceAll(" ");
    }

    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        String out = s
            .replace("&nbsp;", " ")
            .replace("&ensp;", " ")
            .replace("&emsp;", " ")
            .replace("&thinsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&hellip;", "...")
            .replace("&mdash;", " - ")
            .replace("&ndash;", "-")
            .replace("&rsquo;", "'")
            .replace("&lsquo;", "'")
            .replace("&rdquo;", "\"")
            .replace("&ldquo;", "\"")
            .replace("&middot;", " ")
            .replace("&bull;", " ")
            .replace("&trade;", " ")
            .replace("&copy;", " ")
            .replace("&reg;", " ");
        Matcher m = NUMERIC_ENTITY.matcher(out);
        StringBuilder b = new StringBuilder(out.length());
        while (m.find()) {
            String token = m.group(1);
            String replacement;
            try {
                int cp = token.charAt(0) == 'x' || token.charAt(0) == 'X'
                    ? Integer.parseInt(token.substring(1), 16)
                    : Integer.parseInt(token);
                replacement = Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : " ";
            } catch (NumberFormatException e) {
                replacement = " ";
            }
            m.appendReplacement(b, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(b);
        // Ampersand last, so "&amp;lt;" can't be double-decoded into a live tag.
        return b.toString().replace("&amp;", " and ");
    }

    /**
     * Drops emoji/decorative symbols and folds "smart" punctuation down to the
     * plain ASCII a synthesizer pronounces predictably. Code-point-based rather
     * than regex-based so the exact ranges being removed are auditable - see
     * {@link #isSpeechNoiseSymbol} for the Indic-safety argument.
     */
    static String stripSymbolsAndFoldPunctuation(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case 0x00A0, 0x2007, 0x202F, 0x2009, 0x200A -> b.append(' ');
                case 0x2018, 0x2019, 0x201A, 0x201B, 0x2032 -> b.append('\'');
                case 0x201C, 0x201D, 0x201E, 0x201F, 0x2033 -> b.append('"');
                case 0x2010, 0x2011, 0x2012, 0x2015 -> b.append('-');
                case 0x2013, 0x2014 -> b.append(" - ");
                case 0x2026 -> b.append("...");
                case 0x2022, 0x00B7 -> b.append(' ');
                default -> {
                    if (!isSpeechNoiseSymbol(cp)) {
                        b.appendCodePoint(cp);
                    } else {
                        b.append(' ');
                    }
                }
            }
        }
        return b.toString();
    }

    /**
     * Emoji, pictographs and box/geometry decoration - dropped from spoken text.
     *
     * <p><b>Indic safety.</b> Every range below starts at U+2190 or higher.
     * Devanagari (U+0900-U+097F), Bengali (U+0980-U+09FF), Gurmukhi
     * (U+0A00-U+0A7F), Gujarati (U+0A80-U+0AFF), Odia (U+0B00-U+0B7F), Tamil
     * (U+0B80-U+0BFF), Telugu (U+0C00-U+0C7F), Kannada (U+0C80-U+0CFF) and
     * Malayalam (U+0D00-U+0D7F) all sit below U+0E00, so no Indic code point
     * - letter, vowel sign, virama, digit or danda - can fall into any of
     * these ranges. Same for Arabic/Cyrillic/Greek; CJK (U+4E00+) sits below
     * the emoji planes and above every range listed here.
     */
    private static boolean isSpeechNoiseSymbol(int cp) {
        return (cp >= 0x2190 && cp <= 0x21FF)     // arrows
            || (cp >= 0x2300 && cp <= 0x23FF)     // misc technical (watches, media controls)
            || (cp >= 0x2460 && cp <= 0x24FF)     // enclosed alphanumerics
            || (cp >= 0x2500 && cp <= 0x257F)     // box drawing
            || (cp >= 0x2580 && cp <= 0x259F)     // block elements
            || (cp >= 0x25A0 && cp <= 0x25FF)     // geometric shapes
            || (cp >= 0x2600 && cp <= 0x26FF)     // misc symbols
            || (cp >= 0x2700 && cp <= 0x27BF)     // dingbats
            || (cp >= 0x2B00 && cp <= 0x2BFF)     // misc symbols and arrows
            || (cp >= 0xFE00 && cp <= 0xFE0F)     // variation selectors (emoji presentation)
            || cp == 0x20E3                        // combining enclosing keycap
            || (cp >= 0x1F000 && cp <= 0x1FAFF);  // emoji / pictograph planes
    }

    /**
     * Removes ANSI escape sequences (CSI/OSC/two-char) and every other control
     * character except tab/LF/CR, then the Unicode invisible-formatting
     * characters (bidi overrides and isolates, zero-width space/joiner, BOM,
     * soft hyphen, the Unicode Tag block).
     *
     * <p>Deliberately identical in behaviour to {@code
     * McpToolOutputSanitizer#stripControlAndAnsi} in {@code core/agents}: that
     * class is package-private and lives in a module neither TTS client can
     * depend on, so the character classes are restated here. Both a bidi
     * override and a zero-width joiner are silent on screen but are exactly
     * the sort of thing a synthesizer either chokes on or spells out.
     */
    static String stripControlAnsiAndInvisibles(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (c == ESC) {
                i++;
                if (i < n && raw.charAt(i) == '[') {
                    // CSI: parameter/intermediate bytes 0x20-0x3F, then one final byte
                    i++;
                    while (i < n && raw.charAt(i) >= 0x20 && raw.charAt(i) <= 0x3F) {
                        i++;
                    }
                    if (i < n) {
                        i++;
                    }
                } else if (i < n && raw.charAt(i) == ']') {
                    // OSC: runs until BEL or ST (ESC \)
                    i++;
                    while (i < n && raw.charAt(i) != BEL && raw.charAt(i) != ESC) {
                        i++;
                    }
                    if (i < n && raw.charAt(i) == BEL) {
                        i++;
                    } else if (i + 1 < n && raw.charAt(i) == ESC && raw.charAt(i + 1) == '\\') {
                        i += 2;
                    }
                } else if (i < n) {
                    i++;
                }
                continue;
            }
            boolean keep = c == '\t' || c == '\n' || c == '\r'
                || (c >= 0x20 && c != DEL && !(c >= 0x80 && c <= 0x9F));
            if (keep) {
                out.append(c);
            }
            i++;
        }
        return stripInvisibles(out.toString());
    }

    private static String stripInvisibles(String s) {
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                any = true;
                break;
            }
        }
        if (!any) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (!isDisallowedInvisible(cp)) {
                b.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return b.toString();
    }

    private static boolean isDisallowedInvisible(int cp) {
        return (cp >= 0x200B && cp <= 0x200F)   // ZWSP/ZWNJ/ZWJ + LRM/RLM
            || (cp >= 0x202A && cp <= 0x202E)   // bidi embeddings/overrides
            || (cp >= 0x2060 && cp <= 0x2064)   // word joiner + invisible math ops
            || (cp >= 0x2066 && cp <= 0x2069)   // bidi isolates
            || cp == 0xFEFF                      // ZWNBSP / BOM
            || cp == 0x00AD                      // soft hyphen
            || (cp >= 0xE0000 && cp <= 0xE007F); // Unicode Tag block
    }
}
