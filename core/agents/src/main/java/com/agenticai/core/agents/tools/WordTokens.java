package com.agenticai.core.agents.tools;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits text into the words tool selection compares, in any script. A word is a run of letters, the
 * combining marks Indic and Arabic scripts write vowels with, and digits; it used to be {@code
 * [a-z0-9]+}, which left a message in Russian, Hindi, Chinese or Arabic with no words at all, so every
 * tool scored 0 for it and the turn was cut to the few always-on tools whatever the person asked for.
 *
 * <p>Pure and stateless; shared by {@link ToolRelevanceSelector} (the agent's whole tool set) and the
 * small-model tool cap.
 */
public final class WordTokens {

    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");

    private WordTokens() {}

    /**
     * The lower-cased words of {@code text} that are longer than two characters. A word in a script that
     * puts spaces between words is kept whole; text in a script written without spaces (Chinese,
     * Japanese, Thai, ...) has no word boundary to cut at, so it is taken as overlapping pairs of
     * characters, the usual way to match such text without a dictionary. A run that mixes the two is cut
     * where the script changes. No stop-words are removed: that is each caller's choice.
     */
    public static Set<String> words(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Matcher matcher = WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        Set<String> words = new HashSet<>();
        while (matcher.find()) {
            addRun(matcher.group(), words);
        }
        return words;
    }

    /**
     * True when {@code text} has a letter outside the Latin script. Tool names and descriptions are
     * written in Latin script, so a message in another script cannot share a word with them: scoring 0
     * for every tool says nothing about relevance.
     */
    public static boolean hasLettersOutsideLatin(String text) {
        if (text == null) {
            return false;
        }
        return text.codePoints()
                .anyMatch(
                        cp -> {
                            if (!Character.isLetter(cp)) {
                                return false;
                            }
                            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
                            return script != Character.UnicodeScript.LATIN
                                    && script != Character.UnicodeScript.COMMON
                                    && script != Character.UnicodeScript.INHERITED;
                        });
    }

    private static void addRun(String run, Set<String> words) {
        int start = 0;
        while (start < run.length()) {
            boolean unspaced = isUnspacedScript(run.codePointAt(start));
            int end = start;
            while (end < run.length() && isUnspacedScript(run.codePointAt(end)) == unspaced) {
                end += Character.charCount(run.codePointAt(end));
            }
            String part = run.substring(start, end);
            if (unspaced) {
                addPairs(part, words);
            } else if (part.codePointCount(0, part.length()) > 2) {
                words.add(part);
            }
            start = end;
        }
    }

    private static void addPairs(String part, Set<String> words) {
        int[] points = part.codePoints().toArray();
        if (points.length == 1) {
            words.add(part);
            return;
        }
        for (int i = 0; i + 1 < points.length; i++) {
            words.add(new String(points, i, 2));
        }
    }

    private static boolean isUnspacedScript(int codePoint) {
        return switch (Character.UnicodeScript.of(codePoint)) {
            case HAN, HIRAGANA, KATAKANA, THAI, LAO, KHMER, MYANMAR, TIBETAN -> true;
            default -> false;
        };
    }
}
