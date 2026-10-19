package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * {@link Chunker} is the single chunker every ingestion path shares. The
 * properties locked down here are the ones something downstream depends on:
 * no chunk ever exceeds {@code chunkSize} words (the single-embedding-per-call
 * contract of {@link SidecarClient#embedPreChunked}), headings start chunks,
 * paragraphs and sentences aren't cut when they fit, overlap never crosses a
 * heading, and the 150/30 defaults / precedence rules resolve as documented.
 */
class ChunkerTest {

    private static String words(String prefix, int n) {
        return IntStream.range(0, n).mapToObj(i -> prefix + i).collect(Collectors.joining(" "));
    }

    private static final Chunker.Settings SMALL = new Chunker.Settings(20, 5);

    @Test
    void blankTextProducesNoChunks() {
        assertEquals(List.of(), Chunker.chunk("   \n\n\t ", Chunker.Settings.DEFAULTS));
        assertEquals(List.of(), Chunker.chunk(null, Chunker.Settings.DEFAULTS));
    }

    @Test
    void shortTextIsOneChunkWithItsStructureKept() {
        String text = "# Title\n\nFirst paragraph.\n\nSecond paragraph.";
        List<String> chunks = Chunker.chunk(text, Chunker.Settings.DEFAULTS);
        assertEquals(1, chunks.size());
        assertEquals("# Title\n\nFirst paragraph.\n\nSecond paragraph.", chunks.get(0));
    }

    @Test
    void everyHeadingStartsANewChunkAndCarriesItsOwnContent() {
        String text = "# Refunds\nRefunds take five days.\n\n## Shipping\nShipping is free over 500.";
        List<String> chunks = Chunker.chunk(text, Chunker.Settings.DEFAULTS);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).startsWith("# Refunds"));
        assertTrue(chunks.get(0).contains("five days"));
        assertTrue(chunks.get(1).startsWith("## Shipping"));
        assertFalse(chunks.get(1).contains("Refunds"), "no overlap across a section boundary");
    }

    @Test
    void hashtagWithoutASpaceIsNotAHeading() {
        List<String> chunks = Chunker.chunk("intro text\n#hashtag continues here", Chunker.Settings.DEFAULTS);
        assertEquals(1, chunks.size());
    }

    @Test
    void paragraphsArePackedWholeUntilTheNextOneWouldNotFit() {
        String p1 = words("a", 8);
        String p2 = words("b", 8);
        String p3 = words("c", 8);
        List<String> chunks = Chunker.chunk(p1 + "\n\n" + p2 + "\n\n" + p3, new Chunker.Settings(20, 0));
        assertEquals(2, chunks.size());
        assertEquals(p1 + "\n\n" + p2, chunks.get(0));
        assertEquals(p3, chunks.get(1));
    }

    @Test
    void anOverLongParagraphSplitsOnSentenceBoundaries() {
        String s1 = words("x", 12) + ".";
        String s2 = words("y", 12) + "!";
        String s3 = words("z", 12) + "?";
        List<String> chunks = Chunker.chunk(s1 + " " + s2 + " " + s3, new Chunker.Settings(20, 0));
        assertEquals(3, chunks.size());
        assertEquals(s1, chunks.get(0));
        assertEquals(s2, chunks.get(1));
        assertEquals(s3, chunks.get(2));
    }

    @Test
    void devanagariDandaIsASentenceBoundary() {
        String s1 = words("क", 12) + "।";
        String s2 = words("ख", 12) + "।";
        List<String> chunks = Chunker.chunk(s1 + " " + s2, new Chunker.Settings(20, 0));
        assertEquals(List.of(s1, s2), chunks);
    }

    @Test
    void onlyASingleOverLongSentenceFallsBackToTheWordWindowWithOverlap() {
        String sentence = words("w", 50); // no terminator at all
        List<String> chunks = Chunker.chunk(sentence, SMALL);
        // window 20, step 15: [0,20) [15,35) [30,50)
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).startsWith("w0 ") && chunks.get(0).endsWith(" w19"));
        assertTrue(chunks.get(1).startsWith("w15 "), "5-word overlap: " + chunks.get(1));
        assertTrue(chunks.get(2).endsWith(" w49"));
    }

    @Test
    void noChunkEverExceedsChunkSizeWordsIncludingCarriedOverlap() {
        StringBuilder text = new StringBuilder("# Doc\n\n");
        for (int i = 0; i < 30; i++) {
            text.append(words("p" + i + "_", 3 + (i * 7) % 19)).append(i % 3 == 0 ? ". " : "\n\n");
        }
        text.append(words("long", 75));
        for (Chunker.Settings settings : List.of(SMALL, new Chunker.Settings(10, 9), new Chunker.Settings(200, 199), Chunker.Settings.DEFAULTS)) {
            for (String chunk : Chunker.chunk(text.toString(), settings)) {
                assertTrue(Chunker.wordCount(chunk) <= settings.chunkSize(),
                    "chunk of " + Chunker.wordCount(chunk) + " words exceeds " + settings.chunkSize());
                assertFalse(chunk.isBlank());
            }
        }
    }

    @Test
    void overlapCarriesTheTailOfThePreviousChunkWithinASection() {
        String p1 = words("a", 15);
        String p2 = words("b", 10);
        List<String> chunks = Chunker.chunk(p1 + "\n\n" + p2, SMALL);
        assertEquals(2, chunks.size());
        // min(overlap 5, size 20 - next unit 10) = 5 carried words
        assertTrue(chunks.get(1).startsWith("a10 a11 a12 a13 a14"), chunks.get(1));
        assertTrue(chunks.get(1).endsWith("b9"));
    }

    @Test
    void wordCountingTreatsUnicodeWhitespaceLikePythonSplit() {
        // Python's str.split() splits on a no-break space; the sidecar counts words that way.
        assertEquals(2, Chunker.wordCount("alpha beta"));
        assertEquals(2, Chunker.wordCount(" alpha beta "));
    }

    @Test
    void settingsEnforceTheSidecarBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Chunker.Settings(9, 0));
        assertThrows(IllegalArgumentException.class, () -> new Chunker.Settings(201, 0));
        assertThrows(IllegalArgumentException.class, () -> new Chunker.Settings(50, 50));
        assertThrows(IllegalArgumentException.class, () -> new Chunker.Settings(50, -1));
    }

    @Test
    void resolvePrefersRequestThenCollectionThenPlatformDefaults() {
        assertEquals(new Chunker.Settings(150, 30), Chunker.resolve(null, null, null, null));
        assertEquals(new Chunker.Settings(100, 10), Chunker.resolve(null, null, 100, 10));
        assertEquals(new Chunker.Settings(80, 10), Chunker.resolve(80, null, 100, 10));
        assertEquals(new Chunker.Settings(80, 0), Chunker.resolve(80, 0, 100, 10));
    }

    @Test
    void resolveShrinksOnlyAnImplicitDefaultOverlapThatNoLongerFits() {
        // Explicit small size + nobody chose an overlap: default 30 would be >= 20.
        assertEquals(new Chunker.Settings(20, 4), Chunker.resolve(20, null, null, null));
        // An overlap someone DID choose is never silently changed.
        assertThrows(IllegalArgumentException.class, () -> Chunker.resolve(20, null, null, 25));
    }

    @Test
    void firstWordsCapsLongTextAndLeavesShortTextUntouched() {
        assertEquals("a b c", Chunker.firstWords("a b c", 5));
        assertEquals("w0 w1 w2", Chunker.firstWords(words("w", 10), 3));
    }
}
