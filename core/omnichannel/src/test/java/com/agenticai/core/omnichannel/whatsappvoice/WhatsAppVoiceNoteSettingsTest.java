package com.agenticai.core.omnichannel.whatsappvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WhatsAppVoiceNoteSettingsTest {

    @Test
    void defaultsAreOffAndConservative() {
        WhatsAppVoiceNoteSettings d = WhatsAppVoiceNoteSettings.defaults();

        assertFalse(d.enabled());
        assertFalse(d.replyWithVoice());
        assertEquals(60, d.maxSeconds());
        assertEquals(1_048_576L, d.maxBytes());
        assertTrue(d.verifySha256());
        assertEquals("mp3", d.ttsCodec());
    }

    @Test
    void effectiveMaxSecondsIsClampedToSarvamsSynchronousLimit() {
        WhatsAppVoiceNoteSettings d = WhatsAppVoiceNoteSettings.defaults();

        assertEquals(30, d.effectiveMaxSeconds(), "60 configured, 30 effective");
        assertEquals(30, WhatsAppVoiceNoteSettings.SARVAM_REST_MAX_SECONDS);
    }

    @Test
    void aSmallerConfiguredCapIsHonouredAndNonsenseIsRaisedToOne() {
        WhatsAppVoiceNoteSettings d = WhatsAppVoiceNoteSettings.defaults();
        WhatsAppVoiceNoteSettings ten = new WhatsAppVoiceNoteSettings(true, false, 10, d.maxBytes(), 10, 25, 25, 500, 20, 300,
            true, "", "codemix", "", "", "mp3");
        WhatsAppVoiceNoteSettings zero = new WhatsAppVoiceNoteSettings(true, false, 0, d.maxBytes(), 10, 25, 25, 500, 20, 300,
            true, "", "codemix", "", "", "mp3");

        assertEquals(10, ten.effectiveMaxSeconds());
        assertEquals(1, zero.effectiveMaxSeconds());
    }

    @Test
    void withersChangeOnlyTheirOwnFlag() {
        WhatsAppVoiceNoteSettings on = WhatsAppVoiceNoteSettings.defaults().withEnabled(true).withReplyWithVoice(true);

        assertTrue(on.enabled());
        assertTrue(on.replyWithVoice());
        assertEquals(60, on.maxSeconds());
        assertEquals(500, on.replyMaxChars());
    }
}
