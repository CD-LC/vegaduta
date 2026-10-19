package com.agenticai.core.omnichannel.whatsappvoice;

import java.util.OptionalDouble;

/**
 * Reads the duration of an Ogg/Opus file (the container WhatsApp voice notes use, mime
 * {@code audio/ogg; codecs=opus}) straight from the bytes, so an over-long note is refused BEFORE it
 * is sent to a paid speech API and without any new dependency (no ffmpeg, no audio library).
 *
 * <p>Method (RFC 7845): the {@code OpusHead} header carries a 16-bit pre-skip; the granule position
 * of the last Ogg page is the total number of 48 kHz samples; duration = (granule - preSkip) / 48000.
 * Returns empty for anything that is not recognisably Ogg/Opus, in which case callers fall back to
 * the byte-size cap alone (an MP3/AAC/AMR voice note has no cheap duration probe here).
 */
final class OggOpusProbe {

    private static final int OPUS_RATE = 48_000;

    private OggOpusProbe() {
    }

    static boolean isOggOpus(byte[] data) {
        return data != null && data.length > 28 && startsWithOggS(data, 0) && indexOfOpusHead(data) >= 0;
    }

    static OptionalDouble durationSeconds(byte[] data) {
        if (!isOggOpus(data)) {
            return OptionalDouble.empty();
        }
        int head = indexOfOpusHead(data);
        if (head + 12 > data.length) {
            return OptionalDouble.empty();
        }
        int preSkip = (data[head + 10] & 0xFF) | ((data[head + 11] & 0xFF) << 8);
        // Scan backwards for the last page header whose granule position is valid (-1 = no packet ends there).
        for (int i = data.length - 27; i >= 0; i--) {
            if (startsWithOggS(data, i) && data[i + 4] == 0) {
                long granule = 0;
                for (int b = 7; b >= 0; b--) {
                    granule = (granule << 8) | (data[i + 6 + b] & 0xFFL);
                }
                if (granule >= 0) {
                    long samples = Math.max(0L, granule - preSkip);
                    return OptionalDouble.of(samples / (double) OPUS_RATE);
                }
            }
        }
        return OptionalDouble.empty();
    }

    private static boolean startsWithOggS(byte[] d, int at) {
        return at + 4 <= d.length && d[at] == 'O' && d[at + 1] == 'g' && d[at + 2] == 'g' && d[at + 3] == 'S';
    }

    private static int indexOfOpusHead(byte[] d) {
        int limit = Math.min(d.length - 8, 512);
        for (int i = 0; i <= limit; i++) {
            if (d[i] == 'O' && d[i + 1] == 'p' && d[i + 2] == 'u' && d[i + 3] == 's'
                && d[i + 4] == 'H' && d[i + 5] == 'e' && d[i + 6] == 'a' && d[i + 7] == 'd') {
                return i;
            }
        }
        return -1;
    }
}
