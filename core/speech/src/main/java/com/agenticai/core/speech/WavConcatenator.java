package com.agenticai.core.speech;

import java.util.List;

/**
 * Joins several independently-synthesized WAV clips (e.g. one Sarvam TTS call
 * per sentence of a streamed reply) into a single, valid WAV file.
 *
 * <p><b>Why this exists.</b> A WAV file is a RIFF container: an 8-byte
 * "RIFF"+size header, a "WAVE" tag, a {@code fmt } subchunk, and a
 * {@code data} subchunk holding the raw PCM samples - each subchunk carries
 * its own size field. Two WAV files cannot be joined by concatenating their
 * bytes: the result would have two RIFF headers and two {@code data}
 * subchunks in the middle of what a player expects to be one continuous
 * sample stream, and most decoders either truncate at the first {@code data}
 * subchunk's declared size or refuse the file outright. This class instead
 * keeps the FIRST clip's header verbatim (so its sample rate/channels/bit
 * depth - identical across every chunk of one turn, since they all come from
 * the same synthesis call/voice) and rewrites only its {@code RIFF} and
 * {@code data} size fields to cover the summed PCM payload, then appends just
 * the PCM payload (not the header) of every subsequent clip.
 *
 * <p>Used only for the one row persisted per voice turn for {@code
 * VoiceQuotaService}'s ledger - the audio a caller actually HEARS is streamed
 * per-chunk in real time and never passes through this class. If a clip
 * isn't a well-formed RIFF/WAVE file (or has no {@code data} subchunk),
 * {@link #concatenate} treats it as if it weren't there rather than throwing
 * - a single malformed chunk should not lose an otherwise-persistable
 * artifact, and there's no correctness bar this method needs to hold beyond
 * "produce something playable" for an audit/quota row.
 */
public final class WavConcatenator {

    private WavConcatenator() {
    }

    /**
     * @param wavClips one or more complete WAV byte arrays, in playback order
     * @return a single valid WAV file whose PCM payload is the concatenation
     *     of each clip's payload, in order; an empty array if {@code
     *     wavClips} is null/empty; the first clip unmodified if it (or every
     *     clip) isn't parseable as RIFF/WAVE
     */
    public static byte[] concatenate(List<byte[]> wavClips) {
        if (wavClips == null || wavClips.isEmpty()) {
            return new byte[0];
        }
        if (wavClips.size() == 1) {
            return wavClips.get(0);
        }

        byte[] first = wavClips.get(0);
        int[] firstData = findDataChunk(first);
        if (firstData == null) {
            return first;
        }
        int headerLength = firstData[0];

        long totalPcmLength = firstData[1];
        for (int i = 1; i < wavClips.size(); i++) {
            int[] data = findDataChunk(wavClips.get(i));
            if (data != null) {
                totalPcmLength += data[1];
            }
        }

        byte[] out = new byte[(int) (headerLength + totalPcmLength)];
        System.arraycopy(first, 0, out, 0, headerLength);
        // The 4 bytes immediately before the PCM payload are always the
        // "data" subchunk's own size field, regardless of how many other
        // subchunks (LIST, fact, ...) preceded it.
        writeUint32LE(out, headerLength - 4, totalPcmLength);
        // RIFF chunk size = total file size minus the 8-byte "RIFF"+size
        // field itself.
        writeUint32LE(out, 4, headerLength + totalPcmLength - 8);

        int pos = headerLength;
        System.arraycopy(first, firstData[0], out, pos, firstData[1]);
        pos += firstData[1];
        for (int i = 1; i < wavClips.size(); i++) {
            byte[] clip = wavClips.get(i);
            int[] data = findDataChunk(clip);
            if (data == null) {
                continue;
            }
            System.arraycopy(clip, data[0], out, pos, data[1]);
            pos += data[1];
        }
        return out;
    }

    /**
     * Scans a RIFF/WAVE file's subchunks looking for {@code data}.
     *
     * @return {@code {payloadOffset, payloadLength}} of the {@code data}
     *     subchunk's PCM bytes, or {@code null} if {@code wav} isn't a
     *     RIFF/WAVE file or has no {@code data} subchunk
     */
    private static int[] findDataChunk(byte[] wav) {
        if (wav == null || wav.length < 12
            || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F'
            || wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') {
            return null;
        }
        int pos = 12;
        while (pos + 8 <= wav.length) {
            boolean isData = wav[pos] == 'd' && wav[pos + 1] == 'a' && wav[pos + 2] == 't' && wav[pos + 3] == 'a';
            long chunkSize = readUint32LE(wav, pos + 4);
            int payloadOffset = pos + 8;
            if (isData) {
                int len = (int) Math.max(0, Math.min(chunkSize, wav.length - payloadOffset));
                return new int[] {payloadOffset, len};
            }
            // Subchunks are padded to an even byte boundary.
            long advance = chunkSize + (chunkSize % 2);
            if (advance < 0 || payloadOffset + advance > wav.length) {
                return null;
            }
            pos = (int) (payloadOffset + advance);
        }
        return null;
    }

    private static long readUint32LE(byte[] b, int offset) {
        return (b[offset] & 0xFFL) | ((b[offset + 1] & 0xFFL) << 8)
            | ((b[offset + 2] & 0xFFL) << 16) | ((b[offset + 3] & 0xFFL) << 24);
    }

    private static void writeUint32LE(byte[] b, int offset, long value) {
        b[offset] = (byte) (value & 0xFF);
        b[offset + 1] = (byte) ((value >> 8) & 0xFF);
        b[offset + 2] = (byte) ((value >> 16) & 0xFF);
        b[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
