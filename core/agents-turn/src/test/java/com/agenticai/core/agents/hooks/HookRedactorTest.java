package com.agenticai.core.agents.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HookRedactorTest {

    private static HookRedactor.Result redact(
            String text, List<String> builtin, List<String> regex, String replacement) {
        return HookRedactor.redact(text, builtin, regex, replacement, 1_000);
    }

    @Test
    void redactsBuiltInPiiShapes() {
        String text =
                "Mail jo@example.com or call +1 415-555-0132, card 4111 1111 1111 1111, ssn"
                        + " 123-45-6789, pan ABCDE1234F.";

        HookRedactor.Result r =
                redact(
                        text,
                        List.of("email", "phone", "credit_card", "ssn", "pan"),
                        List.of(),
                        null);

        assertFalse(r.text().contains("jo@example.com"));
        assertFalse(r.text().contains("415-555-0132"));
        assertFalse(r.text().contains("4111 1111 1111 1111"));
        assertFalse(r.text().contains("123-45-6789"));
        assertFalse(r.text().contains("ABCDE1234F"));
        assertTrue(r.text().contains("[REDACTED_EMAIL]"));
        assertTrue(r.text().contains("[REDACTED_CARD]"));
        assertEquals(5, r.count());
    }

    @Test
    void onlyTheRequestedPatternsRun() {
        HookRedactor.Result r =
                redact("jo@example.com and 123-45-6789", List.of("email"), List.of(), null);

        assertTrue(r.text().contains("123-45-6789"));
        assertFalse(r.text().contains("jo@example.com"));
        assertEquals(1, r.count());
    }

    @Test
    void aCustomReplacementOverridesEveryPlaceholder() {
        HookRedactor.Result r =
                redact("jo@example.com", List.of("email"), List.of("x{3}"), "[GONE]");

        assertEquals("[GONE]", r.text());
    }

    @Test
    void customRegexRedactsAndCounts() {
        HookRedactor.Result r =
                redact(
                        "token=abc123 and token=zzz999",
                        List.of(),
                        List.of("token=[a-z0-9]+"),
                        null);

        assertEquals("[REDACTED] and [REDACTED]", r.text());
        assertEquals(2, r.count());
    }

    @Test
    void nothingMatchingReturnsTheInputUnchanged() {
        String text = "nothing sensitive here";

        HookRedactor.Result r = redact(text, List.of("email", "phone"), List.of("zzz+"), null);

        assertEquals(text, r.text());
        assertEquals(0, r.count());
    }

    @Test
    void nullAndEmptyInputPassThrough() {
        assertNull(redact(null, List.of("email"), List.of(), null).text());
        assertEquals("", redact("", List.of("email"), List.of(), null).text());
    }

    @Test
    void emptyMatchesAreSkippedRatherThanReplacedEverywhere() {
        // A pattern that can match the empty string is rejected at save time; if one is stored
        // anyway it must not
        // shred the text at run time.
        HookRedactor.Result r = redact("abc", List.of(), List.of("x*"), null);

        assertEquals("abc", r.text());
        assertEquals(0, r.count());
    }

    @Test
    void aRunawayRegexIsAbortedByTheTimeBudgetNotRunToCompletion() {
        String evil = "a".repeat(100_000) + "!";
        long started = System.nanoTime();

        assertThrows(
                HookRedactor.RedactionException.class,
                () -> HookRedactor.redact(evil, List.of(), List.of("a*c"), null, 100));

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(
                elapsedMillis < 5_000,
                "the budget must bound the match, took " + elapsedMillis + "ms");
    }

    @Test
    void validateRegexExplainsWhyAPatternIsRejected() {
        assertNull(HookRedactor.validateRegex("secret-[0-9]+"));
        assertNotNull(HookRedactor.validateRegex(""));
        assertNotNull(HookRedactor.validateRegex("("));
        assertNotNull(HookRedactor.validateRegex("a*"));
        assertNotNull(HookRedactor.validateRegex("a".repeat(HookRedactor.MAX_PATTERN_LENGTH + 1)));
    }

    @Test
    void builtinNamesAreTheDocumentedSet() {
        assertEquals(
                java.util.Set.of("email", "phone", "credit_card", "ssn", "aadhaar", "pan"),
                HookRedactor.builtinNames());
    }

    @Test
    void anAddressIsRedactedWholeWhateverTheLengthOfItsLocalPart() {
        for (int length : new int[] {63, 64, 65, 70, 300, 5_000}) {
            HookRedactor.Result r =
                    redact(
                            "mail " + "a".repeat(length) + "@example.com please",
                            List.of("email"),
                            List.of(),
                            null);
            assertEquals("mail [REDACTED_EMAIL] please", r.text(), "local part of " + length);
            assertEquals(1, r.count());
        }
    }

    @Test
    void anAdversarialTextOf50kCharactersIsRedactedWithinTheBudget() {
        for (String hostile :
                new String[] {
                    "a".repeat(50_000),
                    "a@".repeat(25_000),
                    "a".repeat(63) + "@" + "b.".repeat(25_000)
                }) {
            HookRedactor.Result r =
                    HookRedactor.redact(hostile, List.of("email"), List.of(), null, 5_000);
            assertEquals(hostile, r.text());
            assertEquals(0, r.count());
        }
    }
}
