package com.agenticai.core.quality.trajectory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The matcher bounds author-supplied regexes by counting {@code charAt} reads, on the calling
 * thread - a catastrophically backtracking pattern must come back as "no match" in milliseconds,
 * never hold the eval worker.
 */
class BoundedRegexMatcherTest {

    @Test
    void ordinaryPatternsFindAndMissAsExpected() {
        assertTrue(
                BoundedRegexMatcher.find(
                        Pattern.compile("tool_[a-z]+"), "called tool_search twice"));
        assertFalse(BoundedRegexMatcher.find(Pattern.compile("tool_[a-z]+"), "no calls recorded"));
        assertTrue(BoundedRegexMatcher.find(Pattern.compile("^exact$"), "exact"));
    }

    @Test
    void theDefaultBudgetIsGenerousEnoughForHonestUse() {
        String text = "a".repeat(10_000) + "needle" + "b".repeat(10_000);

        assertTrue(BoundedRegexMatcher.find(Pattern.compile("needle"), text));
        assertFalse(BoundedRegexMatcher.find(Pattern.compile("missing"), text));
    }

    @Test
    void aSpentBudgetReadsAsNoMatch() {
        // One charAt read is not enough to even start matching a 3-char literal.
        assertFalse(BoundedRegexMatcher.find(Pattern.compile("abc"), "xxabcxx", 0));
        assertFalse(BoundedRegexMatcher.find(Pattern.compile("abc"), "xxabcxx", 1));
    }

    @Test
    void aCatastrophicPatternDiesInsideTheBudget() {
        // Classic exponential backtracker: nested quantifiers over a string
        // that can never match. Without the budget this hangs; with it, this
        // call returns quickly and reports "no match".
        Pattern evil = Pattern.compile("(a+)+$");
        String adversary = "a".repeat(10_000) + "b";

        long started = System.nanoTime();
        boolean matched = BoundedRegexMatcher.find(evil, adversary);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertFalse(matched, "the budget abandons the match and reports it as not found");
        assertTrue(
                elapsedMs < 10_000,
                "took " + elapsedMs + " ms - the budget did not bound the work");
    }

    @Test
    void subSequenceSharesTheParentBudget() {
        // Regex internals pull subSequences off the input; each shares the
        // counting wrapper, so reads through a subSequence still draw down the
        // same budget rather than getting a fresh one.
        String text = "x".repeat(100) + "needle";
        assertTrue(BoundedRegexMatcher.find(Pattern.compile("x+needle"), text));
        assertFalse(BoundedRegexMatcher.find(Pattern.compile("x+needle"), text, 3));
    }
}
