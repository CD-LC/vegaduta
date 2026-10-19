package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link FimResponseCleaner} exists because the completion comes back from a
 * CHAT model (the hosted pool has no infill endpoint - see {@link
 * FimTemplate}'s delivery caveat), so this suite locks in the three chat
 * habits that actually corrupt a paste - markdown fences, an echoed prefix,
 * an echoed suffix - and, just as importantly, the cases where cleanup must
 * keep its hands off: a near-miss echo, and leading indentation.
 *
 * <p>Plain JUnit, no Spring and no Mockito - the class under test is static
 * and has no collaborators.
 */
class FimResponseCleanerTest {

    private static final int NO_TRUNCATION = 10_000;

    // ---- fences ------------------------------------------------------------

    @Test
    void aFencedReplyIsUnwrapped() {
        assertEquals(
            "return a + b;",
            FimResponseCleaner.clean("```java\nreturn a + b;\n```", "", "", NO_TRUNCATION)
        );
    }

    @Test
    void aFenceWithNoLanguageTagIsUnwrappedToo() {
        assertEquals("x = 1", FimResponseCleaner.clean("```\nx = 1\n```", "", "", NO_TRUNCATION));
    }

    /**
     * The token cap truncates mid-reply often enough that an unterminated
     * fence is a normal outcome, not a malformed one - the opening backticks
     * still must not reach the editor.
     */
    @Test
    void anUnterminatedFenceIsStillUnwrapped() {
        assertEquals("return a +", FimResponseCleaner.clean("```java\nreturn a +", "", "", NO_TRUNCATION));
    }

    @Test
    void aReplyThatIsNothingButAFenceOpenerYieldsNothing() {
        assertEquals("", FimResponseCleaner.clean("```java", "", "", NO_TRUNCATION));
    }

    @Test
    void backticksInTheMiddleOfRealCodeAreLeftAlone() {
        String raw = "const q = `select ${id}`;";
        assertEquals(raw, FimResponseCleaner.clean(raw, "", "", NO_TRUNCATION));
    }

    // ---- echoed context ----------------------------------------------------

    @Test
    void anEchoedPrefixIsRemoved() {
        String prefix = "function add(a, b) {";
        String raw = "function add(a, b) {\n  return a + b;";
        assertEquals("\n  return a + b;", FimResponseCleaner.clean(raw, prefix, "", NO_TRUNCATION));
    }

    @Test
    void anEchoedSuffixIsRemoved() {
        String suffix = "\n}\n\nmodule.exports = add;\n";
        String raw = "  return a + b;\n}";
        assertEquals("  return a + b;", FimResponseCleaner.clean(raw, "", suffix, NO_TRUNCATION));
    }

    @Test
    void bothEndsAreCleanedInOnePass() {
        String prefix = "def add(a, b):\n";
        String suffix = "\n\nprint(add(1, 2))\n";
        String raw = "```python\ndef add(a, b):\n    return a + b\n\n```";
        assertEquals("    return a + b", FimResponseCleaner.clean(raw, prefix, suffix, NO_TRUNCATION));
    }

    /**
     * Deliberate non-behaviour: echo removal is an exact longest-overlap
     * match, never a fuzzy one. A near-miss stays - a visibly duplicated line
     * is an annoyance the user can see and fix, whereas trimming on a guess
     * eats real completion text silently.
     */
    @Test
    void aNearMissEchoIsLeftAlone() {
        String prefix = "function add(a, b) {";
        String raw = "function  add(a, b) {\n  return a + b;";
        assertEquals(raw, FimResponseCleaner.clean(raw, prefix, "", NO_TRUNCATION));
    }

    // ---- whitespace and truncation ----------------------------------------

    @Test
    void leadingIndentationIsPreservedBecauseItIsPartOfTheCompletion() {
        assertEquals("        return 1;", FimResponseCleaner.clean("        return 1;   ", "", "", NO_TRUNCATION));
    }

    @Test
    void trailingBlankLinesCollapseToASingleNewline() {
        assertEquals("return 1;\n", FimResponseCleaner.clean("return 1;\n\n\n   ", "", "", NO_TRUNCATION));
    }

    @Test
    void anOverlongCompletionIsTruncatedToTheCap() {
        String raw = "y".repeat(50);
        assertEquals("y".repeat(10), FimResponseCleaner.clean(raw, "", "", 10));
    }

    // ---- nothing to say ----------------------------------------------------

    @Test
    void emptyAndBlankRepliesBecomeAnEmptyCompletionRatherThanNull() {
        assertEquals("", FimResponseCleaner.clean(null, "", "", NO_TRUNCATION));
        assertEquals("", FimResponseCleaner.clean("", "", "", NO_TRUNCATION));
        assertEquals("", FimResponseCleaner.clean("   \n  ", "", "", NO_TRUNCATION));
    }

    @Test
    void aReplyThatIsEntirelyEchoBecomesEmpty() {
        assertEquals("", FimResponseCleaner.clean("return a + b;", "return a + b;", "", NO_TRUNCATION));
    }
}
