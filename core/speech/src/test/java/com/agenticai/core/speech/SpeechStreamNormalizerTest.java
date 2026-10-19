package com.agenticai.core.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two failure modes that made per-token normalization impossible, and are
 * therefore the whole reason {@link SpeechStreamNormalizer} exists: a markdown
 * marker split across a token boundary, and words glued together by a
 * normalizer that trims whitespace. Everything else here guards a cut point.
 *
 * <p>Assertions are written against {@link #heard}, the concatenation of every
 * piece sent - that is what the synthesizer at the other end actually speaks,
 * and the piece boundaries themselves are an implementation detail except
 * where a test is specifically about one.
 */
// S2187: every test lives in a @Nested class below; Sonar only descends into @Nested
// when junit-jupiter-api is on its analysis classpath.
@SuppressWarnings("java:S2187")
class SpeechStreamNormalizerTest {

    /** Characters that, left in the string, are what the caller hears read out loud. */
    private static void assertNoSpokenMarkup(String spoken) {
        for (char c : new char[] {'*', '`', '~', '|', '\\'}) {
            assertFalse(spoken.indexOf(c) >= 0,
                "spoken text still contains '" + c + "' (Twilio's TTS will pronounce it): " + spoken);
        }
    }

    /** A clock the test moves by hand - the age threshold is otherwise untestable without sleeping. */
    private static final class TestClock implements LongSupplier {
        private long nanos;

        @Override
        public long getAsLong() {
            return nanos;
        }

        void advanceMillis(long millis) {
            nanos += millis * 1_000_000L;
        }
    }

    /** Default thresholds, frozen clock - so a test about cut points is never disturbed by the age one. */
    private static SpeechStreamNormalizer stopped() {
        return new SpeechStreamNormalizer(
            SpeechStreamNormalizer.DEFAULT_MAX_BUFFERED_CHARS,
            SpeechStreamNormalizer.DEFAULT_MAX_BUFFER_AGE_MILLIS,
            () -> 0L);
    }

    /** Feeds every token, then ends the turn, and returns the pieces that were sent. */
    private static List<String> streamAndComplete(SpeechStreamNormalizer normalizer, String... tokens) {
        List<String> spoken = new ArrayList<>();
        for (String token : tokens) {
            normalizer.accept(token, spoken::add);
        }
        normalizer.flush(spoken::add);
        return spoken;
    }

    /** What the caller hears: the pieces concatenated, exactly as the synthesizer concatenates them. */
    private static String heard(List<String> pieces) {
        return String.join("", pieces);
    }

    private static String[] charTokens(String text) {
        String[] tokens = new String[text.length()];
        for (int i = 0; i < text.length(); i++) {
            tokens[i] = String.valueOf(text.charAt(i));
        }
        return tokens;
    }

    @Nested
    @DisplayName("markdown markers split across token boundaries")
    class SplitMarkers {

        @Test
        @DisplayName("bold arriving as '*' + '*bold' + '*' + '*' is not spoken as asterisks")
        void emphasisSplitAcrossTokens() {
            // Exactly how a tokenizer hands over "**world**" - no single token
            // holds a matched pair, so no per-token rule could ever fire.
            String spoken = heard(streamAndComplete(stopped(),
                "Hello ", "*", "*world", "*", "*", " today."));

            assertEquals("Hello world today.", spoken);
            assertNoSpokenMarkup(spoken);
        }

        @Test
        @DisplayName("the same tokens normalized individually would have spoken the markers - which is the bug")
        void perTokenNormalizationIsWhatThisClassReplaces() {
            // Documents the reason for normalizeFragmentForSpeech: the
            // whole-message normalizer's never-empty fallback hands back the
            // marker itself rather than an empty string, and a per-token
            // caller would send that straight to Twilio.
            assertEquals("*", SpeechTextNormalizer.normalizeForSpeech("*"));
            assertEquals("", SpeechTextNormalizer.normalizeFragmentForSpeech("*"));
        }

        @Test
        @DisplayName("emphasis spanning a sentence boundary loses its markers on both sides of the cut")
        void emphasisSplitAcrossAFlushBoundary() {
            // The buffer HAS to cut at "Yes." (that is a real sentence end),
            // which puts the opening "**" in one piece and the closing one in
            // the next. Each piece must still come out clean.
            String spoken = heard(streamAndComplete(stopped(), "**Yes. ", "Really**"));

            assertEquals("Yes. Really", spoken);
            assertNoSpokenMarkup(spoken);
        }

        @Test
        @DisplayName("a piece that is nothing but markup is not sent at all")
        void markupOnlyPieceIsSilent() {
            List<String> pieces = streamAndComplete(stopped(), "**", "\n", "Hi.");

            assertEquals(List.of("Hi."), pieces);
        }

        @Test
        @DisplayName("a whole markdown reply, streamed one character at a time, has no markup left in it")
        void realisticReplyStreamedCharacterByCharacter() {
            String reply = "## Order 4821\n\nIt **shipped** on Monday - see `tracking-4821` at "
                + "https://example.com/orders/4821 for details.\n\n- Carrier: BlueDart\n"
                + "- ETA: Thursday\n\nAnything else? 😊";

            String spoken = heard(streamAndComplete(stopped(), charTokens(reply)));

            assertNoSpokenMarkup(spoken);
            assertTrue(spoken.contains("It shipped on Monday"), spoken);
            assertTrue(spoken.contains("tracking-4821"), spoken);
            assertTrue(spoken.contains(SpeechTextNormalizer.LINK_PHRASE), spoken);
            assertTrue(spoken.contains("Carrier: BlueDart"), spoken);
            assertFalse(spoken.contains("example.com"), spoken);
        }
    }

    @Nested
    @DisplayName("word boundaries")
    class WordBoundaries {

        @Test
        @DisplayName("a size-forced cut lands on whitespace and the next piece keeps its space")
        void sizeCapCutsAtAWordBoundary() {
            // 'hello' + ' world' is the exact glue case: normalized apart and
            // concatenated they would be spoken as "helloworld".
            List<String> pieces = streamAndComplete(
                new SpeechStreamNormalizer(6, 600, () -> 0L), "hello", " world");

            assertEquals(List.of("hello", " world"), pieces);
            assertEquals("hello world", heard(pieces));
        }

        @Test
        @DisplayName("a long sentence cut into many pieces reassembles with its spacing intact")
        void everyCutPreservesSpacing() {
            String sentence = "the quick brown fox jumps over the lazy dog and keeps running";

            // A 20-char cap over a 61-char sentence forces four cuts, none of
            // which may lose or duplicate a space.
            List<String> pieces = streamAndComplete(
                new SpeechStreamNormalizer(20, 600, () -> 0L), charTokens(sentence));

            assertTrue(pieces.size() > 1, "expected the cap to force several pieces, got " + pieces);
            assertEquals(sentence, heard(pieces));
        }

        @Test
        @DisplayName("the hard-cap cut - the only mid-word one - does not insert a space into the word")
        void hardCapCutDoesNotBreakAWord() {
            // No whitespace anywhere, so there is no word boundary to cut on
            // and the hard cap (3x the soft one) has to cut mid-word.
            String run = "abcdefghijklmno";

            List<String> pieces = streamAndComplete(
                new SpeechStreamNormalizer(4, 600, () -> 0L), charTokens(run));

            assertTrue(pieces.size() > 1, "expected the hard cap to force a cut, got " + pieces);
            assertEquals(run, heard(pieces));
        }
    }

    @Nested
    @DisplayName("sentence boundaries")
    class SentenceBoundaries {

        @Test
        @DisplayName("a finished sentence is spoken without waiting for the rest of the reply")
        void finishedSentenceIsSpokenImmediately() {
            SpeechStreamNormalizer normalizer = stopped();
            List<String> spoken = new ArrayList<>();

            normalizer.accept("Your order shipped. ", spoken::add);

            assertEquals(List.of("Your order shipped."), spoken);
            assertFalse(normalizer.hasPending());
        }

        @Test
        @DisplayName("a full stop inside a decimal is not a sentence boundary")
        void decimalIsNotCut() {
            List<String> pieces = streamAndComplete(stopped(), "Pi is 3.", "14 exactly.");

            assertEquals(List.of("Pi is 3.14 exactly."), pieces);
        }

        @Test
        @DisplayName("a colon inside a clock time is not a sentence boundary")
        void clockTimeIsNotCut() {
            List<String> pieces = streamAndComplete(stopped(), "Meet at 10:", "30 sharp.");

            assertEquals(List.of("Meet at 10:30 sharp."), pieces);
        }

        @Test
        @DisplayName("a newline ends a piece - it is where a heading or list item ends")
        void newlineIsABoundary() {
            SpeechStreamNormalizer normalizer = stopped();
            List<String> spoken = new ArrayList<>();

            normalizer.accept("## Delivery update\n", spoken::add);

            assertEquals(List.of("Delivery update."), spoken);
        }

        @Test
        @DisplayName("a Devanagari danda ends a sentence, and the Indic text survives intact")
        void dandaIsABoundary() {
            List<String> pieces = streamAndComplete(stopped(), "नमस्ते। ", "आपका ऑर्डर भेज दिया गया है।");

            assertEquals(List.of("नमस्ते।", " आपका ऑर्डर भेज दिया गया है।"), pieces);
        }
    }

    @Nested
    @DisplayName("code fences")
    class CodeFences {

        @Test
        @DisplayName("a fenced block is held whole, so it is spoken as one 'code block.'")
        void fenceIsNeverCutThrough() {
            String spoken = heard(streamAndComplete(stopped(),
                "Here you go:\n", "```bash\n", "npm run build\n", "```\n", "Run that."));

            assertEquals("Here you go: code block. Run that.", spoken);
            assertEquals(1, countOccurrences(spoken, SpeechTextNormalizer.CODE_BLOCK_PHRASE),
                "the block was cut into pieces and announced more than once: " + spoken);
            assertFalse(spoken.contains("npm run build"), spoken);
        }

        @Test
        @DisplayName("a fence the model never closes is resolved by the end of the turn")
        void unterminatedFenceIsResolvedOnFlush() {
            String spoken = heard(streamAndComplete(stopped(),
                "Here:\n", "```\n", "rm -rf /tmp/cache\n"));

            assertEquals("Here: code block.", spoken);
            assertFalse(spoken.contains("rm -rf"), spoken);
        }

        @Test
        @DisplayName("strikethrough is not mistaken for a fence")
        void strikethroughIsNotAFence() {
            String spoken = heard(streamAndComplete(stopped(), "It is ~~cancelled~~ confirmed.\n"));

            assertEquals("It is cancelled confirmed.", spoken);
        }

        private static int countOccurrences(String haystack, String needle) {
            int count = 0;
            int from = 0;
            while (true) {
                int at = haystack.indexOf(needle, from);
                if (at < 0) {
                    return count;
                }
                count++;
                from = at + needle.length();
            }
        }
    }

    @Nested
    @DisplayName("age threshold")
    class AgeThreshold {

        @Test
        @DisplayName("a buffer that has stood too long is spoken when the next token arrives")
        void staleBufferIsSpoken() {
            TestClock clock = new TestClock();
            SpeechStreamNormalizer normalizer = new SpeechStreamNormalizer(180, 500, clock);
            List<String> spoken = new ArrayList<>();

            normalizer.accept("still", spoken::add);
            assertEquals(List.of(), spoken, "nothing is due yet");

            clock.advanceMillis(600);
            normalizer.accept(" thinking", spoken::add);

            assertEquals(List.of("still"), spoken);
            assertTrue(normalizer.hasPending());
        }

        @Test
        @DisplayName("a fast stream is not chopped up by the age threshold")
        void freshBufferIsHeld() {
            TestClock clock = new TestClock();
            SpeechStreamNormalizer normalizer = new SpeechStreamNormalizer(180, 500, clock);
            List<String> spoken = new ArrayList<>();

            normalizer.accept("still", spoken::add);
            clock.advanceMillis(50);
            normalizer.accept(" thinking", spoken::add);

            assertEquals(List.of(), spoken);
        }
    }

    @Nested
    @DisplayName("end of turn")
    class EndOfTurn {

        @Test
        @DisplayName("an empty stream says nothing at all")
        void emptyStreamIsSilent() {
            assertEquals(List.of(), streamAndComplete(stopped()));
        }

        @Test
        @DisplayName("null and empty tokens are ignored")
        void nullAndEmptyTokensAreIgnored() {
            SpeechStreamNormalizer normalizer = stopped();
            List<String> spoken = new ArrayList<>();

            normalizer.accept(null, spoken::add);
            normalizer.accept("", spoken::add);

            assertFalse(normalizer.hasPending());
            assertEquals(List.of(), spoken);
        }

        @Test
        @DisplayName("flushing twice does not repeat the last piece")
        void flushIsIdempotent() {
            SpeechStreamNormalizer normalizer = stopped();
            List<String> spoken = new ArrayList<>();

            normalizer.accept("All done", spoken::add);
            normalizer.flush(spoken::add);
            normalizer.flush(spoken::add);

            assertEquals(List.of("All done"), spoken);
        }
    }
}
