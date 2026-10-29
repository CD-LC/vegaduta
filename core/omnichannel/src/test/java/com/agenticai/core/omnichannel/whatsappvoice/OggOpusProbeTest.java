package com.agenticai.core.omnichannel.whatsappvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/**
 * {@link OggOpusProbe}: the dependency-free duration read - last Ogg page granule minus the {@code
 * OpusHead} pre-skip, over 48 kHz.
 */
class OggOpusProbeTest {

    /** A minimal recognisable Ogg/Opus buffer: head page at 0, OpusHead at 28, last page at 60. */
    private static byte[] oggOpus(long granulePosition, int preSkip) {
        byte[] b = new byte[100];
        System.arraycopy("OggS".getBytes(), 0, b, 0, 4);
        System.arraycopy("OpusHead".getBytes(), 0, b, 28, 8);
        b[38] = (byte) (preSkip & 0xFF);
        b[39] = (byte) ((preSkip >> 8) & 0xFF);
        // Last page: "OggS" + version 0 + 8-byte little-endian granule position at offset 6.
        System.arraycopy("OggS".getBytes(), 0, b, 60, 4);
        b[64] = 0;
        for (int i = 0; i < 8; i++) {
            b[66 + i] = (byte) ((granulePosition >> (8 * i)) & 0xFF);
        }
        return b;
    }

    @Test
    void isOggOpusRequiresAllThreeMarkers() {
        assertTrue(OggOpusProbe.isOggOpus(oggOpus(0, 0)));
        assertFalse(OggOpusProbe.isOggOpus(null));
        assertFalse(OggOpusProbe.isOggOpus(new byte[10]));
        assertFalse(OggOpusProbe.isOggOpus(new byte[100]));

        byte[] noHead = oggOpus(0, 0);
        noHead[28] = 'X';
        assertFalse(OggOpusProbe.isOggOpus(noHead));
    }

    @Test
    void durationSecondsSubtractsThePreSkipFromTheLastGranule() {
        // 2 seconds at 48 kHz plus a 312-sample pre-skip.
        OptionalDouble d = OggOpusProbe.durationSeconds(oggOpus(96_000 + 312, 312));
        assertTrue(d.isPresent());
        assertEquals(2.0, d.getAsDouble(), 0.0001);
    }

    @Test
    void durationSecondsIsEmptyForNonOpusInput() {
        assertTrue(OggOpusProbe.durationSeconds(null).isEmpty());
        assertTrue(OggOpusProbe.durationSeconds(new byte[100]).isEmpty());
    }

    @Test
    void durationSecondsIsEmptyWhenTheHeadWouldRunPastTheBuffer() {
        // OpusHead must leave room for the 12 bytes the probe reads after its index.
        byte[] b = new byte[40];
        System.arraycopy("OggS".getBytes(), 0, b, 0, 4);
        System.arraycopy("OpusHead".getBytes(), 0, b, 30, 8);
        assertTrue(OggOpusProbe.durationSeconds(b).isEmpty());
    }
}
