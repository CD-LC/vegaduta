package com.agenticai.core.omnichannel.whatsappvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WhatsAppAudioRefTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    @Test
    void parsesMetasDocumentedVoiceNotePayload() throws Exception {
        // Field names and values follow the example in Meta's audio webhook reference.
        JsonNode message =
                json(
                        """
            {"from":"16505551234","id":"wamid.X","timestamp":"1744344496","type":"audio",
             "audio":{"mime_type":"audio/ogg; codecs=opus","sha256":"wvqXMe6n7n1W0zphvLPoLj+s/NtKqmr3zZ7YzTP7xFI=",
                      "id":"1908647269898587","voice":true}}
            """);

        WhatsAppAudioRef ref = WhatsAppAudioRef.parse(message).orElseThrow();

        assertEquals("1908647269898587", ref.mediaId());
        assertEquals("audio/ogg; codecs=opus", ref.mimeType());
        assertEquals("wvqXMe6n7n1W0zphvLPoLj+s/NtKqmr3zZ7YzTP7xFI=", ref.sha256());
        assertTrue(ref.voice());
    }

    @Test
    void ordinaryAudioFileHasVoiceFalseAndOptionalFieldsMayBeAbsent() throws Exception {
        WhatsAppAudioRef ref =
                WhatsAppAudioRef.parse(
                                json("{\"type\":\"audio\",\"audio\":{\"id\":\"abc_123-x\"}}"))
                        .orElseThrow();

        assertEquals("abc_123-x", ref.mediaId());
        assertFalse(ref.voice());
        assertEquals(null, ref.mimeType());
        assertEquals(null, ref.sha256());
    }

    @Test
    void nonAudioAndMalformedEntriesParseToEmpty() throws Exception {
        assertEquals(
                Optional.empty(),
                WhatsAppAudioRef.parse(json("{\"type\":\"text\",\"text\":{\"body\":\"hi\"}}")));
        assertEquals(Optional.empty(), WhatsAppAudioRef.parse(json("{\"type\":\"audio\"}")));
        assertEquals(
                Optional.empty(),
                WhatsAppAudioRef.parse(json("{\"type\":\"audio\",\"audio\":{}}")));
        assertEquals(Optional.empty(), WhatsAppAudioRef.parse(null));
    }

    @Test
    void mediaIdThatCouldEscapeTheUrlPathIsRejected() throws Exception {
        assertTrue(
                WhatsAppAudioRef.parse(
                                json(
                                        "{\"type\":\"audio\",\"audio\":{\"id\":\"../../me/accounts\"}}"))
                        .isEmpty());
        assertTrue(
                WhatsAppAudioRef.parse(
                                json("{\"type\":\"audio\",\"audio\":{\"id\":\"123?fields=x\"}}"))
                        .isEmpty());
        assertTrue(
                WhatsAppAudioRef.parse(
                                json(
                                        "{\"type\":\"audio\",\"audio\":{\"id\":\""
                                                + "9".repeat(65)
                                                + "\"}}"))
                        .isEmpty());
    }
}
