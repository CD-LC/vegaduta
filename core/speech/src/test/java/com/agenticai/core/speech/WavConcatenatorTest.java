package com.agenticai.core.speech;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Builds small synthetic PCM WAV clips (no real audio needed - only the RIFF
 * container structure matters here) and checks that {@link WavConcatenator}
 * produces one file whose header sizes and PCM payload are exactly the sum of
 * the inputs, not a multi-header file a player would choke on.
 */
class WavConcatenatorTest {

    /** A minimal canonical 44-byte-header PCM WAV: RIFF/WAVE/fmt /data, no extra chunks. */
    private static byte[] wav(byte[] pcm) {
        byte[] out = new byte[44 + pcm.length];
        putAscii(out, 0, "RIFF");
        putUint32LE(out, 4, 36 + pcm.length);
        putAscii(out, 8, "WAVE");
        putAscii(out, 12, "fmt ");
        putUint32LE(out, 16, 16); // fmt subchunk size
        out[20] = 1; out[21] = 0; // PCM
        out[22] = 1; out[23] = 0; // mono
        putUint32LE(out, 24, 16000); // sample rate
        putUint32LE(out, 28, 32000); // byte rate
        out[32] = 2; out[33] = 0; // block align
        out[34] = 16; out[35] = 0; // bits per sample
        putAscii(out, 36, "data");
        putUint32LE(out, 40, pcm.length);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }

    private static void putAscii(byte[] b, int offset, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, b, offset, bytes.length);
    }

    private static void putUint32LE(byte[] b, int offset, long value) {
        b[offset] = (byte) (value & 0xFF);
        b[offset + 1] = (byte) ((value >> 8) & 0xFF);
        b[offset + 2] = (byte) ((value >> 16) & 0xFF);
        b[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static long readUint32LE(byte[] b, int offset) {
        return (b[offset] & 0xFFL) | ((b[offset + 1] & 0xFFL) << 8)
            | ((b[offset + 2] & 0xFFL) << 16) | ((b[offset + 3] & 0xFFL) << 24);
    }

    @Test
    void concatenatesPcmPayloadsInOrder() {
        byte[] a = wav(new byte[] {1, 2, 3, 4});
        byte[] b = wav(new byte[] {5, 6});
        byte[] c = wav(new byte[] {7, 8, 9});

        byte[] joined = WavConcatenator.concatenate(List.of(a, b, c));

        // Exactly one header (44 bytes here, since every input clip shares
        // the same minimal canonical layout) followed by the concatenated PCM.
        assertEquals(44 + 9, joined.length);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9}, Arrays.copyOfRange(joined, 44, joined.length));
    }

    @Test
    void rewritesDataAndRiffSizeFields() {
        byte[] a = wav(new byte[10]);
        byte[] b = wav(new byte[20]);

        byte[] joined = WavConcatenator.concatenate(List.of(a, b));

        assertEquals(30, readUint32LE(joined, 40), "data subchunk size must equal the summed PCM length");
        assertEquals(36 + 30, readUint32LE(joined, 4), "RIFF chunk size must equal (header - 8) + summed PCM length");
    }

    @Test
    void singleClipReturnedUnmodified() {
        byte[] only = wav(new byte[] {42});
        assertArrayEquals(only, WavConcatenator.concatenate(List.of(only)));
    }

    @Test
    void emptyListReturnsEmptyArray() {
        assertEquals(0, WavConcatenator.concatenate(List.of()).length);
        assertEquals(0, WavConcatenator.concatenate(null).length);
    }

    @Test
    void malformedTrailingClipIsSkippedNotThrown() {
        byte[] a = wav(new byte[] {1, 2, 3});
        byte[] notWav = new byte[] {9, 9, 9};

        byte[] joined = WavConcatenator.concatenate(List.of(a, notWav));

        // The malformed clip contributes nothing - output is just the first clip.
        assertEquals(44 + 3, joined.length);
        assertArrayEquals(new byte[] {1, 2, 3}, Arrays.copyOfRange(joined, 44, joined.length));
    }

    @Test
    void firstClipMalformedFallsBackToReturningItUnmodified() {
        byte[] notWav = new byte[] {1, 2, 3, 4};
        byte[] valid = wav(new byte[] {5, 6});

        assertArrayEquals(notWav, WavConcatenator.concatenate(List.of(notWav, valid)));
    }

    @Test
    void extraChunkBeforeDataIsPreservedAndSkippedDuringScan() {
        // A "LIST" chunk (4 bytes of payload) inserted between fmt and data -
        // the scanner must walk past it rather than assuming data starts at
        // a fixed offset.
        byte[] withList = insertListChunk(wav(new byte[] {1, 2}));
        byte[] plain = wav(new byte[] {3, 4});

        byte[] joined = WavConcatenator.concatenate(List.of(withList, plain));

        assertTrue(joined.length > 44 + 4, "header from the first clip (including its LIST chunk) must be preserved");
        assertArrayEquals(new byte[] {1, 2, 3, 4}, Arrays.copyOfRange(joined, joined.length - 4, joined.length));
    }

    /** Splices a 4-byte "LIST" subchunk right after the fmt subchunk (offset 36) of a minimal 44-byte-header clip. */
    private static byte[] insertListChunk(byte[] original) {
        byte[] listChunk = new byte[8 + 4];
        putAscii(listChunk, 0, "LIST");
        putUint32LE(listChunk, 4, 4);
        // 4 bytes of arbitrary payload
        listChunk[8] = 'I'; listChunk[9] = 'N'; listChunk[10] = 'F'; listChunk[11] = 'O';

        byte[] out = new byte[original.length + listChunk.length];
        System.arraycopy(original, 0, out, 0, 36); // RIFF..fmt subchunk (up to but not incl. "data")
        System.arraycopy(listChunk, 0, out, 36, listChunk.length);
        System.arraycopy(original, 36, out, 36 + listChunk.length, original.length - 36);

        // Fix up the RIFF chunk size to account for the inserted bytes.
        long newRiffSize = readUint32LE(out, 4) + listChunk.length;
        putUint32LE(out, 4, newRiffSize);
        return out;
    }
}
