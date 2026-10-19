package com.agenticai.core.omnichannel.whatsappvoice;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Hand-built audio containers for tests - no real recordings, no codec library. */
public final class TestAudio {

    private TestAudio() {}

    /**
     * A minimal two-page Ogg/Opus stream whose last page carries {@code granule} (48 kHz samples).
     */
    public static byte[] oggOpus(long granule, int preSkip) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = new byte[19];
        System.arraycopy("OpusHead".getBytes(StandardCharsets.US_ASCII), 0, head, 0, 8);
        head[8] = 1;
        head[9] = 1;
        head[10] = (byte) (preSkip & 0xFF);
        head[11] = (byte) ((preSkip >> 8) & 0xFF);
        page(out, 0x02, 0L, head);
        page(out, 0x04, granule, new byte[] {1, 2, 3});
        return out.toByteArray();
    }

    /** An Ogg stream that is NOT Opus (Vorbis-style header). */
    public static byte[] oggVorbis() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = new byte[30];
        System.arraycopy("\u0001vorbis".getBytes(StandardCharsets.ISO_8859_1), 0, head, 0, 7);
        page(out, 0x02, 0L, head);
        return out.toByteArray();
    }

    public static byte[] mp3() {
        byte[] b = new byte[64];
        b[0] = 'I';
        b[1] = 'D';
        b[2] = '3';
        return b;
    }

    public static byte[] wav() {
        byte[] b = new byte[64];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, b, 8, 4);
        return b;
    }

    private static void page(ByteArrayOutputStream out, int flags, long granule, byte[] payload) {
        out.writeBytes("OggS".getBytes(StandardCharsets.US_ASCII));
        out.write(0);
        out.write(flags);
        for (int i = 0; i < 8; i++) {
            out.write((int) ((granule >> (8 * i)) & 0xFF));
        }
        out.writeBytes(new byte[12]);
        out.write(1);
        out.write(payload.length);
        out.writeBytes(payload);
    }
}
