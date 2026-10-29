package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link WordTokens} decides what counts as a word for tool relevance scoring. These pin the two
 * script rules the selection logic depends on: spaced scripts keep whole words longer than two
 * characters, and unspaced scripts (Han, kana, Thai...) contribute overlapping character pairs so a
 * message with no spaces still produces matchable tokens.
 */
class WordTokensTest {

    @Test
    void nullAndBlankTextProduceNoWords() {
        assertTrue(WordTokens.words(null).isEmpty());
        assertTrue(WordTokens.words("").isEmpty());
        assertTrue(WordTokens.words("   \n\t ").isEmpty());
    }

    @Test
    void spacedWordsAreLowerCasedAndShortOnesDropped() {
        Set<String> words = WordTokens.words("Search the WEB for Files");

        assertEquals(Set.of("search", "the", "web", "for", "files"), words);
        // Two characters or fewer never make a word.
        assertTrue(WordTokens.words("a an is to").isEmpty());
    }

    @Test
    void punctuationAndDigitsSplitRuns() {
        Set<String> words = WordTokens.words("send_email,please! file-2024");

        assertTrue(words.contains("send"));
        assertTrue(words.contains("email"));
        assertTrue(words.contains("please"));
        assertTrue(words.contains("file"));
        assertTrue(words.contains("2024"));
        assertFalse(words.contains("send_email"), "the underscore is punctuation, not part of a word");
    }

    @Test
    void unspacedScriptsContributeOverlappingPairs() {
        Set<String> words = WordTokens.words("漢字テスト");

        // No word boundary to cut at: the run becomes overlapping 2-character pairs.
        assertTrue(words.contains("漢字"));
        assertTrue(words.contains("字テ"));
        assertTrue(words.contains("テス"));
        assertTrue(words.contains("スト"));
        assertEquals(4, words.size());
        // A single unspaced character is kept whole rather than dropped.
        assertEquals(Set.of("漢"), WordTokens.words("漢"));
    }

    @Test
    void lettersOutsideLatinAreDetected() {
        assertTrue(WordTokens.hasLettersOutsideLatin("Привет"));
        assertTrue(WordTokens.hasLettersOutsideLatin("नमस्ते"));
        assertTrue(WordTokens.hasLettersOutsideLatin("مرحبا"));
        assertTrue(WordTokens.hasLettersOutsideLatin("hello 世界"));

        assertFalse(WordTokens.hasLettersOutsideLatin(null));
        assertFalse(WordTokens.hasLettersOutsideLatin(""));
        assertFalse(WordTokens.hasLettersOutsideLatin("hello world"));
        assertFalse(WordTokens.hasLettersOutsideLatin("12345 !!!"),
            "digits and punctuation carry no script signal");
    }
}
