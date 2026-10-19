package com.agenticai.core.knowledge;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the sentence inside a chunk that best answers a query, so a citation can
 * point at a span ({@code [start, end)} offsets into the chunk text plus the quote)
 * instead of the whole chunk. Pure lexical overlap, no model call: deterministic,
 * free, and good enough to let a reader (or a verifier) check the claim against
 * the exact words.
 *
 * <p>Script awareness: sentences end at Latin {@code . ! ?} (followed by whitespace), at CJK
 * full-width terminators and at the Devanagari danda, none of which need a following space.
 * Terms are whole words for space-delimited scripts (combining marks kept, so Hindi words are not
 * shredded) and character bigrams for Han/Hiragana/Katakana/Hangul runs, which have no spaces.
 * Navigation/cookie/footer boilerplate is never chosen as the best sentence.
 */
public final class CitationSpans {

    /** Longest quote returned; a longer best sentence is cut at this many chars. */
    static final int MAX_QUOTE_CHARS = 400;

    private static final Pattern SENTENCE_END = Pattern.compile(
        "[.!?]+[\"')\\]]*(\\s+|$)"
            + "|[\u3002\uFF01\uFF1F\uFF61\u0964\u0965\u061F\u06D4]+[\u300D\u300F\uFF09\"')\\]]*\\s*"
            + "|\\n{2,}");
    private static final Pattern LEGACY_SENTENCE_END = Pattern.compile("[.!?]+[\"')\\]]*(\\s+|$)|\\n{2,}");
    private static final Pattern LEGACY_WORD = Pattern.compile("[\\p{L}\\p{N}]{3,}");
    private static final Pattern TERMINAL =
        Pattern.compile("[.!?\u3002\uFF01\uFF1F\uFF61\u0964\u0965\u061F\u06D4][\"')\\]\u300D\u300F\uFF09]*$");
    private static final Pattern NAV_SEPARATOR = Pattern.compile("\\s[|\u2022\u00B7\u00BB\u203A]\\s|\\|");

    private static final String[] STRONG_BOILERPLATE = {
        "accept all cookies", "we use cookies", "this site uses cookies", "this website uses cookies",
        "cookie policy", "cookie settings", "manage cookies", "privacy policy", "terms of service",
        "terms of use", "terms and conditions", "all rights reserved", "skip to content", "skip to main",
        "back to top", "subscribe to our newsletter", "sign up for our newsletter", "follow us on",
        "share on facebook", "share on twitter", "copyright \u00A9", "\u00A9 20",
        "\u0915\u0941\u0915\u0940", // Devanagari "cookie"
        "\u0938\u0930\u094D\u0935\u093E\u0927\u093F\u0915\u093E\u0930 \u0938\u0941\u0930\u0915\u094D\u0937\u093F\u0924", // all rights reserved
        "\u9690\u79C1\u653F\u7B56", // privacy policy (zh)
        "\u7248\u6743\u6240\u6709", // all rights reserved (zh)
        "\u30D7\u30E9\u30A4\u30D0\u30B7\u30FC\u30DD\u30EA\u30B7\u30FC" // privacy policy (ja)
    };
    private static final String[] WEAK_BOILERPLATE = {
        "sign in", "log in", "login", "sign up", "subscribe", "menu", "home", "contact us", "about us", "search", "share"
    };

    /** A located span; {@code end} is exclusive and {@code quote == text.substring(start, end)}. */
    public record Span(int start, int end, String quote) {}

    private CitationSpans() {}

    /**
     * @return the best-matching sentence span, or null if the inputs are blank or no
     *     sentence shares a term with the query
     */
    public static Span best(String text, String query) {
        return best(text, query, false);
    }

    /**
     * Research variant: script-aware sentence splitting and terms, and boilerplate lines are never chosen.
     * {@link #best(String, String)} keeps its ORIGINAL behaviour (Latin-only splitting and word regex, no
     * boilerplate filter) because KnowledgeSearchService's citation spans depend on it.
     */
    public static Span bestForResearch(String text, String query) {
        return best(text, query, true);
    }

    private static Span best(String text, String query, boolean research) {
        if (text == null || text.isBlank() || query == null || query.isBlank()) {
            return null;
        }
        Set<String> terms = research ? termSet(query) : legacyTerms(query);
        if (terms.isEmpty()) {
            return null;
        }
        int bestStart = -1;
        int bestEnd = -1;
        int bestScore = 0;
        int sentenceStart = 0;
        Matcher m = (research ? SENTENCE_END : LEGACY_SENTENCE_END).matcher(text);
        while (true) {
            boolean found = m.find();
            int sentenceEnd = found ? m.end() : text.length();
            int[] trimmed = trim(text, sentenceStart, sentenceEnd);
            if (trimmed != null) {
                String sentence = text.substring(trimmed[0], trimmed[1]);
                if (!research || !isBoilerplate(sentence)) {
                    int score = research ? overlap(sentence, terms) : legacyOverlap(sentence, terms);
                    if (score > bestScore) {
                        bestScore = score;
                        bestStart = trimmed[0];
                        bestEnd = trimmed[1];
                    }
                }
            }
            if (!found) {
                break;
            }
            sentenceStart = sentenceEnd;
        }
        if (bestScore == 0) {
            return null;
        }
        if (bestEnd - bestStart > MAX_QUOTE_CHARS) {
            bestEnd = bestStart + MAX_QUOTE_CHARS;
        }
        return new Span(bestStart, bestEnd, text.substring(bestStart, bestEnd));
    }

    /**
     * Locates {@code quote} in {@code text} (exact first, then whitespace- and case-insensitive).
     *
     * @return the span in the ORIGINAL text, or null if the quote does not occur
     */
    public static Span locate(String text, String quote) {
        if (text == null || quote == null || quote.isBlank()) {
            return null;
        }
        int exact = text.indexOf(quote);
        if (exact >= 0) {
            return new Span(exact, exact + quote.length(), quote);
        }
        String[] tokens = quote.strip().split("\\s+");
        StringBuilder rx = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                rx.append("\\s+");
            }
            rx.append(Pattern.quote(tokens[i]));
        }
        Matcher found = Pattern.compile(rx.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        if (found.find()) {
            return new Span(found.start(), found.end(), text.substring(found.start(), found.end()));
        }
        return null;
    }

    /**
     * Content terms of {@code text}, lower-cased: whole words of 3+ code points for space-delimited
     * scripts (combining marks included, so Devanagari/Thai words stay whole) and overlapping character
     * bigrams for Han/Hiragana/Katakana/Hangul runs.
     */
    public static Set<String> termSet(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        int wordLen = 0;
        int i = 0;
        while (i < lower.length()) {
            int cp = lower.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                wordLen = flushWord(word, wordLen, out);
                cjk.appendCodePoint(cp);
            } else if (Character.isLetterOrDigit(cp) || isMark(cp)) {
                flushCjk(cjk, out);
                word.appendCodePoint(cp);
                wordLen++;
            } else {
                wordLen = flushWord(word, wordLen, out);
                flushCjk(cjk, out);
            }
        }
        flushWord(word, wordLen, out);
        flushCjk(cjk, out);
        return out;
    }

    /**
     * True for navigation, cookie-banner, footer and label lines that must never be offered as evidence:
     * known boilerplate phrases, pipe/bullet-separated menus, and very short fragments with no sentence
     * punctuation ("Home", "About Us").
     */
    public static boolean isBoilerplate(String line) {
        if (line == null) {
            return true;
        }
        String s = line.strip();
        if (s.isEmpty()) {
            return true;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        int len = s.codePointCount(0, s.length());
        for (String phrase : STRONG_BOILERPLATE) {
            // 120, not longer: a real sentence that merely MENTIONS a privacy policy or a copyright is
            // longer than a footer/banner line and must stay citable.
            if (lower.contains(phrase) && len <= 120) {
                return true;
            }
        }
        Matcher sep = NAV_SEPARATOR.matcher(s);
        int seps = 0;
        while (sep.find()) {
            seps++;
        }
        if (seps >= 2) {
            return true;
        }
        boolean terminal = TERMINAL.matcher(s).find();
        int words = s.split("\\s+").length;
        boolean hasCjk = s.codePoints().anyMatch(CitationSpans::isCjk);
        boolean hasDigit = s.codePoints().anyMatch(Character::isDigit);
        // Unpunctuated fragments are dropped only when tiny and number-free: "Pricing", "Contact us" go,
        // a three-word heading carrying a figure ("Q3 revenue 4.2M") stays.
        if (!terminal && !hasDigit && (hasCjk ? len < 6 : words <= 2)) {
            return true;
        }
        if (words <= 8 && !terminal) {
            for (String phrase : WEAK_BOILERPLATE) {
                if (lower.equals(phrase) || lower.startsWith(phrase + " ") || lower.endsWith(" " + phrase)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Drops boilerplate lines from extracted page text, keeping the remaining lines and their newlines.
     * The returned string is a NEW offset space: store and cite against it, not the original.
     */
    public static String stripBoilerplate(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
                continue;
            }
            if (isBoilerplate(line)) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString().strip();
    }

    private static int flushWord(StringBuilder word, int wordLen, Set<String> out) {
        if (wordLen >= 3) {
            out.add(word.toString());
        }
        word.setLength(0);
        return 0;
    }

    private static void flushCjk(StringBuilder cjk, Set<String> out) {
        int[] cps = cjk.codePoints().toArray();
        for (int k = 0; k + 1 < cps.length; k++) {
            out.add(new String(cps, k, 2));
        }
        cjk.setLength(0);
    }

    private static boolean isMark(int cp) {
        int t = Character.getType(cp);
        return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK || t == Character.ENCLOSING_MARK;
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
        return sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA
            || sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HANGUL;
    }

    private static int[] trim(String text, int start, int end) {
        int s = start;
        int e = end;
        while (s < e && Character.isWhitespace(text.charAt(s))) {
            s++;
        }
        while (e > s && Character.isWhitespace(text.charAt(e - 1))) {
            e--;
        }
        return s < e ? new int[] {s, e} : null;
    }

    private static Set<String> legacyTerms(String query) {
        Set<String> out = new HashSet<>();
        Matcher w = LEGACY_WORD.matcher(query.toLowerCase(Locale.ROOT));
        while (w.find()) {
            out.add(w.group());
        }
        return out;
    }

    private static int legacyOverlap(String sentence, Set<String> terms) {
        Set<String> seen = new HashSet<>();
        Matcher w = LEGACY_WORD.matcher(sentence.toLowerCase(Locale.ROOT));
        while (w.find()) {
            if (terms.contains(w.group())) {
                seen.add(w.group());
            }
        }
        return seen.size();
    }

    private static int overlap(String sentence, Set<String> terms) {
        Set<String> seen = new HashSet<>(termSet(sentence));
        seen.retainAll(terms);
        return seen.size();
    }
}
