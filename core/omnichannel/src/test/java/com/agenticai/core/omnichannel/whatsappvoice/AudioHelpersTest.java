package com.agenticai.core.omnichannel.whatsappvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class AudioHelpersTest {

    @Test
    void oggOpusDurationIsGranuleMinusPreSkipOver48k() {
        // 10 s of audio = 480000 samples, plus a 312-sample pre-skip carried in the granule.
        byte[] audio = TestAudio.oggOpus(480_000L + 312L, 312);

        OptionalDouble seconds = OggOpusProbe.durationSeconds(audio);

        assertTrue(seconds.isPresent());
        assertEquals(10.0, seconds.getAsDouble(), 0.0001);
    }

    @Test
    void oggOpusDurationIsEmptyForNonOpusAndGarbage() {
        assertTrue(OggOpusProbe.durationSeconds(TestAudio.oggVorbis()).isEmpty());
        assertTrue(OggOpusProbe.durationSeconds(TestAudio.mp3()).isEmpty());
        assertTrue(OggOpusProbe.durationSeconds(new byte[0]).isEmpty());
        assertTrue(OggOpusProbe.durationSeconds(null).isEmpty());
    }

    @Test
    void oggOpusWithNoValidGranuleIsEmptyNotZero() {
        // -1 granule (no packet finishes on the page) must not be reported as a duration.
        byte[] audio = TestAudio.oggOpus(-1L, 0);
        // The first page's granule is 0 (valid), so the scan falls back to it: 0 seconds, never negative.
        OptionalDouble seconds = OggOpusProbe.durationSeconds(audio);
        assertTrue(seconds.isEmpty() || seconds.getAsDouble() == 0.0);
    }

    @Test
    void sniffRecognisesTheContainersWhatsAppUses() {
        assertEquals(AudioFormatSniffer.Kind.OGG_OPUS, AudioFormatSniffer.sniff(TestAudio.oggOpus(48_000L, 0)));
        assertEquals(AudioFormatSniffer.Kind.MP3, AudioFormatSniffer.sniff(TestAudio.mp3()));
        assertEquals(AudioFormatSniffer.Kind.MP3, AudioFormatSniffer.sniff(new byte[] {(byte) 0xFF, (byte) 0xFB, 0x10, 0x00}));
        assertEquals(AudioFormatSniffer.Kind.AAC, AudioFormatSniffer.sniff(new byte[] {(byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80}));
        assertEquals(AudioFormatSniffer.Kind.AMR, AudioFormatSniffer.sniff("#!AMR\n".getBytes()));
        assertEquals(AudioFormatSniffer.Kind.MP4,
            AudioFormatSniffer.sniff(new byte[] {0, 0, 0, 32, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '}));
    }

    @Test
    void wavIsRecognisedAndRejectedForWhatsApp() {
        AudioFormatSniffer.Kind kind = AudioFormatSniffer.sniff(TestAudio.wav());

        assertEquals(AudioFormatSniffer.Kind.WAV, kind);
        assertFalse(kind.whatsAppAccepts());
    }

    @Test
    void textAndVorbisAreNotAudioForOurPurposes() {
        assertEquals(AudioFormatSniffer.Kind.UNKNOWN, AudioFormatSniffer.sniff("<html>error</html>".getBytes()));
        assertEquals(AudioFormatSniffer.Kind.UNKNOWN, AudioFormatSniffer.sniff("{\"error\":1}".getBytes()));
        assertEquals(AudioFormatSniffer.Kind.UNKNOWN, AudioFormatSniffer.sniff(TestAudio.oggVorbis()));
        assertEquals(AudioFormatSniffer.Kind.UNKNOWN, AudioFormatSniffer.sniff(new byte[2]));
        assertEquals(AudioFormatSniffer.Kind.UNKNOWN, AudioFormatSniffer.sniff(null));
    }

    @Test
    void acceptedKindsCarryTheMimeWhatsAppDocuments() {
        assertEquals("audio/mpeg", AudioFormatSniffer.Kind.MP3.mime());
        assertEquals("audio/ogg; codecs=opus", AudioFormatSniffer.Kind.OGG_OPUS.mime());
        assertTrue(AudioFormatSniffer.Kind.MP3.whatsAppAccepts());
        assertFalse(AudioFormatSniffer.Kind.UNKNOWN.whatsAppAccepts());
    }
}
