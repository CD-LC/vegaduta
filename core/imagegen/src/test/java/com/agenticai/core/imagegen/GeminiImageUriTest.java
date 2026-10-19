package com.agenticai.core.imagegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** The API key rides in the URL, so building it must never throw a message that quotes the key. */
class GeminiImageUriTest {

    private static final String HOST = "https://generativelanguage.googleapis.com";

    @Test
    void anOrdinaryKeyIsUnchanged() {
        URI uri = GeminiImageGenerationClient.generateContentUri(HOST, "gemini-2.5-flash-image", " AIzaSyA-b_c123 ");

        assertEquals(
            HOST + "/v1beta/models/gemini-2.5-flash-image:generateContent?key=AIzaSyA-b_c123", uri.toString());
    }

    @Test
    void aHandPastedKeyWithSpacesAndQuotesIsEncodedInsteadOfThrowing() {
        URI uri = GeminiImageGenerationClient.generateContentUri(HOST, "m", "ab cd\"ef&x=1");

        assertEquals("ab cd\"ef&x=1", java.net.URLDecoder.decode(uri.getRawQuery().substring("key=".length()),
            java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(uri.getRawQuery().contains("&"), "the key cannot add a second query parameter");
    }

    @Test
    void aBadHostStillThrowsButTheCallerIsToldNotToChainIt() {
        assertThrows(IllegalArgumentException.class,
            () -> GeminiImageGenerationClient.generateContentUri("https://bad host", "m", "secretkey"));
    }
}
