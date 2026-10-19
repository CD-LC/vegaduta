package com.agenticai.core.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression tests for the catastrophic-backtracking (ReDoS) and
 * stack-exhaustion findings in {@link SpeechTextNormalizer}, and the proof
 * that fixing them cost none of the transformation rules that
 * {@code SpeechTextNormalizerTest} pins.
 *
 * <p>Several rules here are "open delimiter, run of anything, close
 * delimiter". When the close delimiter never arrives, a backtracking engine
 * retries at every one of the n start offsets and each retry rescans -
 * quadratic. Measured on this class before the fix, one core, JDK 24:
 *
 * <pre>
 *   input                          8 KB       32 KB      128 KB
 *   "&lt;script&gt;" repeated       12,114 ms   163,925 ms       -
 *   "&lt;!--" repeated               5,027 ms    84,696 ms       -
 *   "x@" + "a." repeated           2,745 ms    43,605 ms       -
 *   "[a](" repeated                  271 ms     5,241 ms   79,566 ms
 * </pre>
 *
 * <p>Separately, {@code (?:group)+} is the one regex shape Java implements
 * with a JVM stack frame per iteration, and two rules used it unbounded:
 * 8,000 characters of {@code "- "} or {@code ", "} threw
 * {@link StackOverflowError} straight out of
 * {@link SpeechTextNormalizer#normalizeForSpeech}. Being an {@code Error}
 * rather than an exception, it went past every caller's
 * {@code catch (Exception e)} and 500'd the request - reachable with an
 * ordinary long agent reply, no attacker required.
 *
 * <p>The wall-clock bound below is deliberately loose. The fixed
 * implementation answers every one of these in well under 300 ms, so an order
 * of magnitude of CI noise still passes, while a reintroduced unbounded
 * quantifier fails by two to three orders of magnitude.
 */
@DisplayName("SpeechTextNormalizer - ReDoS and stack-exhaustion regression")
// S2187: every test lives in a @Nested class below; Sonar only descends into @Nested
// when junit-jupiter-api is on its analysis classpath.
@SuppressWarnings("java:S2187")
class SpeechTextNormalizerReDoSTest {

    /** Loose enough not to flake on a loaded CI box, tight enough that 170 seconds cannot slip through. */
    private static final Duration BUDGET = Duration.ofSeconds(3);

    private static Duration timeNormalize(String raw) {
        long startNanos = System.nanoTime();
        SpeechTextNormalizer.normalizeForSpeech(raw);
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    @Nested
    @DisplayName("pathological input")
    class Pathological {

        /**
         * One unterminated-delimiter shape per rule that used to be
         * quadratic, each repeated well past {@link
         * SpeechTextNormalizer#MAX_INPUT_CHARS} so both layers of the fix are
         * exercised: the bounded quantifiers AND the input cap.
         */
        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "<script",     // attribute list that never reaches '>'
            "<script>",    // block that never reaches '</script>'
            "<style>",
            "<!--",        // comment that never closes
            "[",           // markdown link text that never closes
            "[a](",        // markdown link target that never closes
            "``a",         // double-backtick inline code that never closes
            "<a ",         // HTML tag that never closes
            "<https://",   // autolink that never closes
            "&#",          // numeric entity that never closes
            "```\n"        // code fence that never closes
        })
        @DisplayName("32 KB of an unterminated delimiter completes well under the budget")
        void unterminatedDelimiterIsFast(String seed) {
            String attack = seed.repeat(32_000 / seed.length() + 1);
            assertThat(attack.length()).isGreaterThan(3 * SpeechTextNormalizer.MAX_INPUT_CHARS);

            Duration elapsed = timeNormalize(attack);

            assertThat(elapsed)
                .as("normalizeForSpeech() on %d chars of %s took %d ms",
                    attack.length(), seed, elapsed.toMillis())
                .isLessThan(BUDGET);
        }

        @Test
        @DisplayName("the email rule's ambiguous domain run no longer costs 44 seconds")
        void ambiguousEmailDomainIsFast() {
            // The old domain class contained '.' itself AND was followed by a
            // required '.', so the engine had O(n) ways to choose the final
            // dot. Labels anchored on a literal dot are unambiguous.
            String attack = "x@" + "a.".repeat(16_000);

            Duration elapsed = timeNormalize(attack);

            assertThat(elapsed)
                .as("normalizeForSpeech() took %d ms", elapsed.toMillis())
                .isLessThan(BUDGET);
        }

        @Test
        @DisplayName("a long run of rule characters no longer throws StackOverflowError")
        void longHorizontalRuleDoesNotBlowTheStack() {
            // "(?:[-*_][ \t]*){3,}" was one JVM frame per repetition.
            String attack = "- ".repeat(4_000);

            assertThatCode(() -> SpeechTextNormalizer.normalizeForSpeech(attack)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a long run of repeated punctuation no longer throws StackOverflowError")
        void longPunctuationRunDoesNotBlowTheStack() {
            // "(?:[ \t]*[,;:])+" was one JVM frame per repetition.
            String attack = ", ".repeat(4_000);

            assertThatCode(() -> SpeechTextNormalizer.normalizeForSpeech(attack)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("input cap")
    class InputCap {

        @Test
        @DisplayName("input past the cap is truncated rather than rejected, and never returns empty")
        void overLongInputIsTruncatedNotRejected() {
            // The callers hand over an agent reply mid-turn; throwing would
            // turn a long-but-legitimate answer into a 500 (see
            // MAX_INPUT_CHARS' javadoc for the per-provider limits this sits
            // above).
            String reply = "Sentence number one. ".repeat(2_000);
            assertThat(reply.length()).isGreaterThan(SpeechTextNormalizer.MAX_INPUT_CHARS);

            String spoken = SpeechTextNormalizer.normalizeForSpeech(reply);

            assertThat(spoken).isNotNull().isNotEmpty();
            assertThat(spoken.length()).isLessThanOrEqualTo(SpeechTextNormalizer.MAX_INPUT_CHARS);
            assertThat(spoken).startsWith("Sentence number one.");
        }

        @Test
        @DisplayName("the cap sits above every synthesizer's own per-request limit")
        void capIsAboveEveryProviderLimit() {
            // ElevenLabs 5,000 / Twilio <Say> 4,096 / Sarvam bulbul ~1,500 -
            // pinned so the cap cannot be tightened below a working request
            // without this failing.
            assertThat(SpeechTextNormalizer.MAX_INPUT_CHARS).isGreaterThanOrEqualTo(5_000);
        }

        @Test
        @DisplayName("input at or under the cap is untouched by it")
        void underCapIsUnaffected() {
            String reply = "a ".repeat(SpeechTextNormalizer.MAX_INPUT_CHARS / 4).strip();

            assertThat(SpeechTextNormalizer.normalizeForSpeech(reply)).isEqualTo(reply);
        }
    }

    @Nested
    @DisplayName("the rewritten rules still behave")
    class SemanticsPreserved {

        @Test
        @DisplayName("bare emails still become the spoken phrase")
        void emails() {
            assertThat(SpeechTextNormalizer.normalizeForSpeech("Mail support@example.com please"))
                .isEqualTo("Mail " + SpeechTextNormalizer.EMAIL_PHRASE + " please");
            assertThat(SpeechTextNormalizer.normalizeForSpeech("Write to john.doe@example.co.uk today"))
                .isEqualTo("Write to " + SpeechTextNormalizer.EMAIL_PHRASE + " today");
        }

        @Test
        @DisplayName("links, images and reference links still keep their text and drop the target")
        void links() {
            assertThat(SpeechTextNormalizer.normalizeForSpeech("See [our docs](https://example.com/docs) now"))
                .isEqualTo("See our docs now");
            assertThat(SpeechTextNormalizer.normalizeForSpeech("![A chart](https://x.test/c.png) shows growth"))
                .isEqualTo("A chart shows growth");
            assertThat(SpeechTextNormalizer.normalizeForSpeech("See [our docs][ref] now"))
                .isEqualTo("See our docs now");
        }

        @Test
        @DisplayName("script and style bodies are still never spoken")
        void scriptAndStyle() {
            String out = SpeechTextNormalizer.normalizeForSpeech(
                "Before<script>alert('pwned')</script><style>.a{color:red}</style><!-- hidden -->After");

            assertThat(out).contains("Before").contains("After")
                .doesNotContain("alert").doesNotContain("color").doesNotContain("hidden");
        }

        @Test
        @DisplayName("HTML tags and autolinks are still stripped")
        void tagsAndAutolinks() {
            assertThat(SpeechTextNormalizer.normalizeForSpeech("<div class=\"x\"><b>Bold</b> here</div>"))
                .isEqualTo("Bold here");
            assertThat(SpeechTextNormalizer.normalizeForSpeech("Try <https://example.com> first"))
                .isEqualTo("Try " + SpeechTextNormalizer.LINK_PHRASE + " first");
        }

        @Test
        @DisplayName("horizontal rules are still recognised now that the check is not a regex")
        void horizontalRules() {
            assertThat(SpeechTextNormalizer.normalizeForSpeech("Above\n\n---\n\nBelow")).isEqualTo("Above. Below");
            assertThat(SpeechTextNormalizer.normalizeForSpeech("Above\n\n***\n\nBelow")).isEqualTo("Above. Below");
            assertThat(SpeechTextNormalizer.normalizeForSpeech("Above\n\n___\n\nBelow")).isEqualTo("Above. Below");
            assertThat(SpeechTextNormalizer.normalizeForSpeech("Above\n\n- - -\n\nBelow")).isEqualTo("Above. Below");
            // Still not a rule: a bullet has content after the marker.
            assertThat(SpeechTextNormalizer.normalizeForSpeech("- item one")).isEqualTo("item one.");
        }

        @Test
        @DisplayName("repeated punctuation still collapses")
        void repeatedPunctuation() {
            assertThat(SpeechTextNormalizer.normalizeForSpeech("Wait, , , then go")).isEqualTo("Wait, then go");
        }
    }
}
