package com.agenticai.core.omnichannel.whatsappvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * {@link AudioFormatSniffer} (and the {@link OggOpusProbe} it delegates to): magic-byte
 * identification of every container WhatsApp does and does not accept.
 */
class AudioFormatSnifferTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] oggOpus() {
        // "OggS" page header with an OpusHead marker inside the first 512 bytes.
        byte[] b = new byte[64];
        byte[] magic = bytes("OggS");
        System.arraycopy(magic, 0, b, 0, 4);
        byte[] head = bytes("OpusHead");
        System.arraycopy(head, 0, b, 28, 8);
        return b;
    }

    @Test
    void wavIsRecognizedAndNotWhatsAppAcceptable() {
        byte[] wav = "RIFF....WAVE".getBytes(StandardCharsets.US_ASCII);
        AudioFormatSniffer.Kind kind = AudioFormatSniffer.sniff(wav);
        assertEquals(AudioFormatSniffer.Kind.WAV, kind);
        assertEquals("audio/wav", kind.mime());
        assertEquals("wav", kind.extension());
        assertFalse(kind.whatsAppAccepts());
    }

    @Test
    void riffWithoutTheWaveMarkerIsNotWav() {
        assertEquals(
                AudioFormatSniffer.Kind.UNKNOWN,
                AudioFormatSniffer.sniff("RIFF....NOPE".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void oggIsOpusOnlyWhenTheOpusHeadMarkerIsPresent() {
        assertEquals(AudioFormatSniffer.Kind.OGG_OPUS, AudioFormatSniffer.sniff(oggOpus()));

        byte[] vorbis = new byte[64];
        System.arraycopy(bytes("OggS"), 0, vorbis, 0, 4);
        System.arraycopy(bytes("vorbis"), 0, vorbis, 28, 6);
        assertEquals(AudioFormatSniffer.Kind.UNKNOWN, AudioFormatSniffer.sniff(vorbis));
    }

    @Test
    void mp4IsDetectedByTheFtypBox() {
        byte[] mp4 = new byte[16];
        System.arraycopy(bytes("ftyp"), 0, mp4, 4, 4);
        assertEquals(AudioFormatSniffer.Kind.MP4, AudioFormatSniffer.sniff(mp4));
    }

    @Test
    void amrAndId3HaveTheirOwnSignatures() {
        assertEquals(
                AudioFormatSniffer.Kind.AMR,
                AudioFormatSniffer.sniff("#!AMR\n".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(
                AudioFormatSniffer.Kind.MP3,
                AudioFormatSniffer.sniff("ID3x".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void mpegFrameSyncSplitsAacFromMp3OnTheLayerBits() {
        // 0xFF 0xF1: sync + layer bits 00 -> ADTS (AAC).
        assertEquals(
                AudioFormatSniffer.Kind.AAC,
                AudioFormatSniffer.sniff(new byte[] {(byte) 0xFF, (byte) 0xF1, 0, 0}));
        // 0xFF 0xFB: sync + layer bits 01 -> MPEG Layer III.
        assertEquals(
                AudioFormatSniffer.Kind.MP3,
                AudioFormatSniffer.sniff(new byte[] {(byte) 0xFF, (byte) 0xFB, 0, 0}));
        // 0xFF 0xF5: layer bits 10 -> neither of the two recognized formats.
        assertEquals(
                AudioFormatSniffer.Kind.UNKNOWN,
                AudioFormatSniffer.sniff(new byte[] {(byte) 0xFF, (byte) 0xF5, 0, 0}));
    }

    @Test
    void nullEmptyAndShortInputAreUnknown() {
        assertEquals(AudioFormatSniffer.Kind.UNKNOWN, AudioFormatSniffer.sniff(null));
        assertEquals(AudioFormatSniffer.Kind.UNKNOWN, AudioFormatSniffer.sniff(new byte[0]));
        assertEquals(AudioFormatSniffer.Kind.UNKNOWN, AudioFormatSniffer.sniff(new byte[3]));
    }

    @Test
    void everyAcceptedFormatAdvertisesItself() {
        assertTrue(AudioFormatSniffer.Kind.OGG_OPUS.whatsAppAccepts());
        assertEquals("audio/ogg; codecs=opus", AudioFormatSniffer.Kind.OGG_OPUS.mime());
        assertEquals("ogg", AudioFormatSniffer.Kind.OGG_OPUS.extension());
        assertEquals("application/octet-stream", AudioFormatSniffer.Kind.UNKNOWN.mime());
        assertEquals("bin", AudioFormatSniffer.Kind.UNKNOWN.extension());
        assertFalse(AudioFormatSniffer.Kind.UNKNOWN.whatsAppAccepts());
    }
}
