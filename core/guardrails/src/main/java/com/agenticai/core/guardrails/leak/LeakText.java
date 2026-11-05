package com.agenticai.core.guardrails.leak;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

/**
 * Text reduced to what survives cosmetic evasion, with a map back to the original characters so a
 * match can be cut out of the raw text.
 *
 * <p>The reduction ("compact" form) keeps only letters and digits and drops everything else:
 *
 * <ul>
 *   <li>NFKC normalisation per code point (full-width and mathematical-alphabet letters, ligatures
 *       and similar fold to plain letters);
 *   <li>format characters (zero-width space/joiner/non-joiner, word joiner, BOM, soft hyphen, bidi
 *       controls), control characters and combining marks are removed;
 *   <li>a small table of Cyrillic and Greek look-alikes folds to Latin;
 *   <li>case is folded; whitespace, punctuation and markdown ({@code * _ # > ` |}) are dropped.
 * </ul>
 *
 * Dropping spaces too means a reply with different spacing, line breaks, bullets or inserted
 * zero-width characters still compacts to the same string as the prompt.
 *
 * <p>Not covered: paraphrase, translation, encodings (base64, rot13, leetspeak), letter-by-letter
 * spelling with other text between the letters, and homoglyphs outside the table.
 */
final class LeakText {

    private static final String HOMOGLYPH_FROM = "аеорсхуіјѕкмнтвёαεοιυνρτκχ";
    private static final String HOMOGLYPH_TO = "aeopcxyijskmhtbeaeoiuvptkx";

    final String compact;
    private final int[] rawStart;
    private final int[] rawEnd;

    private LeakText(String compact, int[] rawStart, int[] rawEnd) {
        this.compact = compact;
        this.rawStart = rawStart;
        this.rawEnd = rawEnd;
    }

    static LeakText of(CharSequence raw) {
        int n = raw.length();
        StringBuilder out = new StringBuilder(n);
        int[] starts = new int[Math.max(16, n)];
        int[] ends = new int[Math.max(16, n)];
        int size = 0;
        for (int i = 0; i < n; ) {
            int cp = Character.codePointAt(raw, i);
            int len = Character.charCount(cp);
            int end = i + len;
            i = end;
            int type = Character.getType(cp);
            if (type == Character.FORMAT
                    || type == Character.CONTROL
                    || type == Character.NON_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK) {
                continue;
            }
            String folded =
                    Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKC);
            for (int k = 0; k < folded.length(); k++) {
                char c = fold(folded.charAt(k));
                if (!Character.isLetterOrDigit(c)) {
                    continue;
                }
                if (size == starts.length) {
                    starts = Arrays.copyOf(starts, size * 2);
                    ends = Arrays.copyOf(ends, size * 2);
                }
                out.append(c);
                starts[size] = end - len;
                ends[size] = end;
                size++;
            }
        }
        return new LeakText(out.toString(), Arrays.copyOf(starts, size), Arrays.copyOf(ends, size));
    }

    private static char fold(char c) {
        char lower = Character.toLowerCase(c);
        int idx = HOMOGLYPH_FROM.indexOf(lower);
        return idx >= 0 ? HOMOGLYPH_TO.charAt(idx) : lower;
    }

    int length() {
        return compact.length();
    }

    /** Index in the raw text of the first character of compact character {@code j}. */
    int rawStart(int j) {
        return rawStart[j];
    }

    /** Index in the raw text just after compact character {@code j}. */
    int rawEnd(int j) {
        return rawEnd[j];
    }

    /** Compact form of a token, for comparison with {@link #compact}. */
    static String compactOf(String token) {
        return of(token == null ? "" : token).compact.toLowerCase(Locale.ROOT);
    }
}
