package com.agenticai.core.imagegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.agenticai.core.imagegen.VideoGenerationController.ByteRange;
import org.junit.jupiter.api.Test;

/**
 * Covers both of {@code /api/videogen/videos/{id}}'s Range gates:
 *
 * <ul>
 *   <li>{@link VideoGenerationController#resolveRange} - the mainline since
 *       {@code FileStorageService.size()} landed (X-2, 2026-08-04): all
 *       three RFC 7233 single-range forms (bounded, open-ended, suffix)
 *       resolved against the known total, {@code null} for
 *       ignore-and-serve-200 (absent/malformed/multi-range), and the
 *       identity-compared {@link ByteRange#UNSATISFIABLE} sentinel for
 *       416.</li>
 *   <li>{@link VideoGenerationController#parseBoundedRange} - now only the
 *       degraded path for when the size lookup fails, where a single fully
 *       bounded {@code bytes=start-end} range is the one form still
 *       expressible (206 with {@code Content-Range: .../*}) and everything
 *       else falls back to a full 200.</li>
 * </ul>
 */
class VideoGenerationControllerRangeParsingTest {

    @Test
    void nullHeaderIsNotARange() {
        assertNull(VideoGenerationController.parseBoundedRange(null));
    }

    @Test
    void missingBytesPrefixIsRejected() {
        assertNull(VideoGenerationController.parseBoundedRange("0-499"));
    }

    @Test
    void wellFormedBoundedRangeParses() {
        assertArrayEquals(new long[] {0, 499}, VideoGenerationController.parseBoundedRange("bytes=0-499"));
        assertArrayEquals(new long[] {500, 1023}, VideoGenerationController.parseBoundedRange("bytes=500-1023"));
    }

    @Test
    void wellFormedBoundedRangeToleratesWhitespace() {
        assertArrayEquals(new long[] {0, 499}, VideoGenerationController.parseBoundedRange("  bytes=0-499  "));
    }

    @Test
    void openEndedRangeIsNotSatisfiableWithoutAKnownLength() {
        // "bytes=500-" (read to EOF) - can't be resolved into a correct
        // Content-Range without knowing the object's total length, which
        // FileStorageService doesn't expose. Falls back to a full 200.
        assertNull(VideoGenerationController.parseBoundedRange("bytes=500-"));
    }

    @Test
    void suffixRangeIsNotSatisfiableWithoutAKnownLength() {
        // "bytes=-500" (last 500 bytes) - same reason as the open-ended case.
        assertNull(VideoGenerationController.parseBoundedRange("bytes=-500"));
    }

    @Test
    void multipleRangesAreRejected() {
        assertNull(VideoGenerationController.parseBoundedRange("bytes=0-99,200-299"));
    }

    @Test
    void invertedOrNegativeRangeIsRejected() {
        assertNull(VideoGenerationController.parseBoundedRange("bytes=500-100"));
        assertNull(VideoGenerationController.parseBoundedRange("bytes=-5-10"));
    }

    @Test
    void nonNumericRangeIsRejected() {
        assertNull(VideoGenerationController.parseBoundedRange("bytes=abc-def"));
    }

    // --- resolveRange(header, totalLength) - the size-aware mainline ---------

    private static final long TOTAL = 1000;

    @Test
    void resolveNullHeaderMeansServeTheFullBody() {
        assertNull(VideoGenerationController.resolveRange(null, TOTAL));
    }

    @Test
    void resolveMissingBytesPrefixIsIgnored() {
        assertNull(VideoGenerationController.resolveRange("0-499", TOTAL));
        assertNull(VideoGenerationController.resolveRange("items=0-499", TOTAL));
    }

    @Test
    void resolveBoundedRangeWithinTheObjectIsServedAsIs() {
        assertEquals(new ByteRange(0, 499), VideoGenerationController.resolveRange("bytes=0-499", TOTAL));
        assertEquals(new ByteRange(500, 999), VideoGenerationController.resolveRange("bytes=500-999", TOTAL));
    }

    @Test
    void resolveBoundedRangeToleratesWhitespace() {
        assertEquals(new ByteRange(0, 499), VideoGenerationController.resolveRange("  bytes=0-499  ", TOTAL));
    }

    @Test
    void resolveBoundedEndPastEofIsClampedToTheLastByte() {
        // RFC 7233 §2.1: an end beyond the representation's length means
        // "to EOF", not an error.
        assertEquals(new ByteRange(900, 999), VideoGenerationController.resolveRange("bytes=900-5000", TOTAL));
    }

    @Test
    void resolveBoundedStartAtOrPastEofIs416() {
        assertSame(ByteRange.UNSATISFIABLE, VideoGenerationController.resolveRange("bytes=1000-1099", TOTAL));
        assertSame(ByteRange.UNSATISFIABLE, VideoGenerationController.resolveRange("bytes=5000-6000", TOTAL));
    }

    @Test
    void resolveOpenEndedRangeRunsToEof() {
        // The form browsers actually send when a viewer seeks - the case the
        // whole X-2 change exists for.
        assertEquals(new ByteRange(500, 999), VideoGenerationController.resolveRange("bytes=500-", TOTAL));
        assertEquals(new ByteRange(0, 999), VideoGenerationController.resolveRange("bytes=0-", TOTAL));
    }

    @Test
    void resolveOpenEndedRangeStartingPastEofIs416() {
        assertSame(ByteRange.UNSATISFIABLE, VideoGenerationController.resolveRange("bytes=1000-", TOTAL));
    }

    @Test
    void resolveSuffixRangeServesTheLastNBytes() {
        assertEquals(new ByteRange(500, 999), VideoGenerationController.resolveRange("bytes=-500", TOTAL));
    }

    @Test
    void resolveSuffixLongerThanTheObjectServesTheWholeObject() {
        // §2.1: a suffix-length >= the representation length means the
        // entire representation.
        assertEquals(new ByteRange(0, 999), VideoGenerationController.resolveRange("bytes=-5000", TOTAL));
    }

    @Test
    void resolveZeroLengthSuffixIs416() {
        // §2.1 calls a zero suffix-length explicitly unsatisfiable.
        assertSame(ByteRange.UNSATISFIABLE, VideoGenerationController.resolveRange("bytes=-0", TOTAL));
    }

    @Test
    void resolveAnyRangeAgainstAnEmptyObjectIs416() {
        assertSame(ByteRange.UNSATISFIABLE, VideoGenerationController.resolveRange("bytes=0-0", 0));
        assertSame(ByteRange.UNSATISFIABLE, VideoGenerationController.resolveRange("bytes=0-", 0));
        assertSame(ByteRange.UNSATISFIABLE, VideoGenerationController.resolveRange("bytes=-10", 0));
    }

    @Test
    void resolveMultipleRangesFallBackToTheFullBody() {
        // Documented decision (class javadoc): multipart/byteranges assembly
        // is complexity no video element needs - RFC 7233 §3.1 permits
        // ignoring the header, so a full 200 is served instead.
        assertNull(VideoGenerationController.resolveRange("bytes=0-99,200-299", TOTAL));
    }

    @Test
    void resolveInvertedOrMalformedRangeFallsBackToTheFullBody() {
        assertNull(VideoGenerationController.resolveRange("bytes=500-100", TOTAL));
        assertNull(VideoGenerationController.resolveRange("bytes=-5-10", TOTAL));
        assertNull(VideoGenerationController.resolveRange("bytes=abc-def", TOTAL));
        assertNull(VideoGenerationController.resolveRange("bytes=", TOTAL));
        assertNull(VideoGenerationController.resolveRange("bytes=42", TOTAL));
    }
}
