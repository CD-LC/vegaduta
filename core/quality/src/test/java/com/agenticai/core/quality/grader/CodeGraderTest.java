package com.agenticai.core.quality.grader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CodeGraderTest {

    @Test
    void regexFindsAnywhereByDefaultAndHonoursIgnoreCase() {
        CodeGrader g = CodeGrader.parse("{\"type\":\"regex\",\"pattern\":\"\\\\bseine\\\\b\",\"ignoreCase\":true}");
        assertEquals(CodeGrader.Kind.REGEX, g.kind());
        assertTrue(g.grade("It is the River SEINE, in Paris.").passed());
        assertFalse(g.grade("The Thames").passed());
        assertFalse(g.grade(null).passed());
    }

    @Test
    void regexMatchesModeNeedsTheWholeResponse() {
        CodeGrader g = CodeGrader.parse("{\"type\":\"regex\",\"pattern\":\"\\\\d+\",\"mode\":\"matches\"}");
        assertTrue(g.grade("42").passed());
        assertFalse(g.grade("the answer is 42").passed());
    }

    @Test
    void badRegexAndUnknownTypeAreRejectedAtParseTime() {
        assertThrows(IllegalArgumentException.class, () -> CodeGrader.parse("{\"type\":\"regex\",\"pattern\":\"(\"}"));
        assertThrows(IllegalArgumentException.class, () -> CodeGrader.parse("{\"type\":\"fuzzy\"}"));
        assertThrows(IllegalArgumentException.class, () -> CodeGrader.parse("not json"));
        assertThrows(IllegalArgumentException.class, () -> CodeGrader.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> CodeGrader.parse("[1]"));
    }

    @Test
    void catastrophicRegexFailsTheCaseInsteadOfHangingTheWorker() {
        CodeGrader g = CodeGrader.parse("{\"type\":\"regex\",\"pattern\":\"(a+)+$\"}");
        String evil = "a".repeat(60) + "!";
        CodeGrader.Result r = g.grade(evil);
        assertFalse(r.passed());
    }

    @Test
    void exactTrimsByDefaultAndCanIgnoreCase() {
        CodeGrader g = CodeGrader.parse("{\"type\":\"exact\",\"value\":\"Tokyo\",\"ignoreCase\":true}");
        assertTrue(g.grade("  tokyo\n").passed());
        assertFalse(g.grade("Tokyo, Japan").passed());
        CodeGrader strict = CodeGrader.parse("{\"type\":\"exact\",\"value\":\"2\",\"trim\":false}");
        assertFalse(strict.grade("2\n").passed());
        assertTrue(strict.grade("2").passed());
    }

    private static final String SCHEMA = "{\"type\":\"json_schema\",\"schema\":{"
        + "\"type\":\"object\",\"required\":[\"country\",\"n\"],\"additionalProperties\":false,"
        + "\"properties\":{\"country\":{\"type\":\"string\",\"enum\":[\"Japan\"]},"
        + "\"n\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":5}}}}";

    @Test
    void jsonSchemaAcceptsBareFencedAndProseWrappedJson() {
        CodeGrader g = CodeGrader.parse(SCHEMA);
        assertTrue(g.grade("{\"country\":\"Japan\",\"n\":3}").passed());
        assertTrue(g.grade("```json\n{\"country\":\"Japan\",\"n\":3}\n```").passed());
        assertTrue(g.grade("Sure! Here you go: {\"country\": \"Japan\", \"n\": 3} hope that helps").passed());
    }

    @Test
    void jsonSchemaReportsViolations() {
        CodeGrader g = CodeGrader.parse(SCHEMA);
        CodeGrader.Result wrongEnum = g.grade("{\"country\":\"Chad\",\"n\":3}");
        assertFalse(wrongEnum.passed());
        assertTrue(wrongEnum.detail().contains("enum"));
        assertFalse(g.grade("{\"country\":\"Japan\"}").passed());            // missing n
        assertFalse(g.grade("{\"country\":\"Japan\",\"n\":9}").passed());    // above maximum
        assertFalse(g.grade("{\"country\":\"Japan\",\"n\":1,\"x\":1}").passed()); // extra property
        assertFalse(g.grade("{\"country\":\"Japan\",\"n\":1.5}").passed());  // not an integer
        CodeGrader.Result none = g.grade("I could not find it.");
        assertFalse(none.passed());
        assertTrue(none.detail().contains("no JSON"));
    }

    @Test
    void jsonSchemaHandlesArraysAnyOfAndBracesInsideStrings() {
        CodeGrader g = CodeGrader.parse("{\"type\":\"json_schema\",\"schema\":{\"type\":\"array\","
            + "\"minItems\":1,\"items\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}}}");
        assertTrue(g.grade("[\"a } b\", null]").passed());
        assertFalse(g.grade("[1]").passed());
        assertFalse(g.grade("[]").passed());
    }

    @Test
    void unsupportedSchemaKeywordsAreRejectedNotIgnored() {
        assertThrows(IllegalArgumentException.class, () -> CodeGrader.parse(
            "{\"type\":\"json_schema\",\"schema\":{\"oneOf\":[{\"type\":\"string\"}]}}"));
        assertThrows(IllegalArgumentException.class, () -> CodeGrader.parse(
            "{\"type\":\"json_schema\",\"schema\":{\"$ref\":\"#/x\"}}"));
        assertThrows(IllegalArgumentException.class, () -> CodeGrader.parse(
            "{\"type\":\"json_schema\",\"schema\":{\"properties\":{\"a\":{\"format\":\"email\"}}}}"));
        // Annotations are fine.
        CodeGrader.parse("{\"type\":\"json_schema\",\"schema\":{\"title\":\"t\",\"description\":\"d\",\"type\":\"object\"}}");
    }

    @Test
    void recursivePatternOnALongResponseFailsTheCaseInsteadOfKillingTheWorker() {
        CodeGrader g = CodeGrader.parse("{\"type\":\"regex\",\"pattern\":\"(a|b)*c\",\"mode\":\"matches\"}");
        String longInput = "ab".repeat(100_000);
        CodeGrader.Result r = g.grade(longInput); // must not throw StackOverflowError
        assertFalse(r.passed());
    }

    @Test
    void catastrophicPatternIsCutOffByTheStepBudget() {
        CodeGrader g = CodeGrader.parse("{\"type\":\"regex\",\"pattern\":\"(x+x+)+y\"}");
        CodeGrader.Result r = g.grade("x".repeat(60));
        assertFalse(r.passed());
    }

    @Test
    void alternationBacktrackingPatternIsCutOffByWallClockTimeout() {
        CodeGrader g = CodeGrader.parse("{\"type\":\"regex\",\"pattern\":\"(a|aa)+$\"}");
        long start = System.nanoTime();
        CodeGrader.Result r = g.grade("a".repeat(5000) + "b");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertFalse(r.passed());
        assertTrue(r.detail().contains("aborted"), r.detail());
        assertTrue(elapsedMs < CodeGrader.REGEX_TIMEOUT_MS + 3000L, "caller must be released by the timeout: " + elapsedMs);
    }

    /** A text whose length() blocks: the engine never reaches charAt, so only the wall-clock timeout can release the caller. */
    private static final class BlockingText implements CharSequence {
        @Override public int length() {
            try {
                Thread.sleep(60_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }
        @Override public char charAt(int index) { return 'a'; }
        @Override public CharSequence subSequence(int start, int end) { return this; }
    }

    @Test
    void engineThatNeverCallsCharAtIsReleasedByTheWallClockTimeout() {
        long start = System.nanoTime();
        assertThrows(CodeGrader.BudgetExceeded.class,
            () -> CodeGrader.matchBounded(java.util.regex.Pattern.compile("a+"), new BlockingText(), false));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs >= CodeGrader.REGEX_TIMEOUT_MS - 100L && elapsedMs < CodeGrader.REGEX_TIMEOUT_MS + 3000L,
            "released by the timeout, not earlier/later: " + elapsedMs);
    }

    @Test
    void saturatedPoolFailsFastInsteadOfQueueing() throws Exception {
        // Pin all 8 pool threads on blocking texts, then a 9th call must be rejected immediately.
        java.util.concurrent.ExecutorService callers = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 8; i++) {
                callers.submit(() -> {
                    try {
                        CodeGrader.matchBounded(java.util.regex.Pattern.compile("a+"), new BlockingText(), false);
                    } catch (CodeGrader.BudgetExceeded expected) {
                        // released by timeout
                    }
                });
            }
            Thread.sleep(300L);
            long start = System.nanoTime();
            assertThrows(CodeGrader.BudgetExceeded.class,
                () -> CodeGrader.matchBounded(java.util.regex.Pattern.compile("a+"), "aaa", false));
            assertTrue((System.nanoTime() - start) / 1_000_000L < CodeGrader.REGEX_TIMEOUT_MS / 2,
                "saturated pool must fail fast");
        } finally {
            callers.shutdownNow();
            Thread.sleep(200L);
        }
    }

    @Test
    void exponentialPatternReportsBudgetNotJustAnyAbort() {
        // JDK 24 handles (x+x+)+y quickly; ((a+)+)+$ is still exponential on it (verified), so it really needs the time bound.
        CodeGrader g = CodeGrader.parse("{\"type\":\"regex\",\"pattern\":\"((a+)+)+$\"}");
        CodeGrader.Result r = g.grade("a".repeat(35) + "!");
        assertFalse(r.passed());
        assertTrue(r.detail().contains("budget exceeded"), r.detail());
    }

    @Test
    void tooLongSchemaPatternIsRejectedAtWriteTime() {
        String longPattern = "a".repeat(CodeGrader.MAX_PATTERN_LENGTH + 1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> CodeGrader.parse(
            "{\"type\":\"json_schema\",\"schema\":{\"type\":\"string\",\"pattern\":\"" + longPattern + "\"}}"));
    }

    @Test
    void jsonExtractGivesUpOnAHugeUnbalancedResponseQuickly() {
        String hostile = "{".repeat(200_000);
        long start = System.nanoTime();
        assertEquals(null, JsonExtract.firstJson(hostile));
        assertTrue((System.nanoTime() - start) < 5_000_000_000L, "firstJson must not be quadratic");
    }

    @Test
    void jsonExtractStillFindsJsonAfterAStrayBrace() {
        assertEquals(1, JsonExtract.firstJson("note {x} then {\"a\":1}").path("a").asInt());
    }

    @Test
    void schemaPatternIsBoundedToo() {
        CodeGrader g = CodeGrader.parse(
            "{\"type\":\"json_schema\",\"schema\":{\"type\":\"string\",\"pattern\":\"(x+x+)+y\"}}");
        CodeGrader.Result r = g.grade("\"" + "x".repeat(60) + "\"");
        assertFalse(r.passed());
    }
}
