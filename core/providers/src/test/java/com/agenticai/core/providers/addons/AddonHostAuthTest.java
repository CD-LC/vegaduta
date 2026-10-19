package com.agenticai.core.providers.addons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AddonHostAuthTest {

    private static final String TOKEN = "k".repeat(AddonHostAuth.MIN_LENGTH);

    private static AddonException fails(AddonHostAuth auth, String header) {
        return assertThrows(AddonException.class, () -> auth.require(header));
    }

    @Test
    void aCorrectBearerTokenPasses() {
        AddonHostAuth auth = new AddonHostAuth(TOKEN);
        assertTrue(auth.configured());
        auth.require("Bearer " + TOKEN);
        auth.require("bearer   " + TOKEN + "  ");
    }

    @Test
    void missingWrongOrDifferentSchemeIs401() {
        AddonHostAuth auth = new AddonHostAuth(TOKEN);
        for (String header : new String[] {
            null, "", "Bearer", "Bearer ", "Bearer " + TOKEN + "x", "Bearer " + TOKEN.substring(1), "Basic " + TOKEN, TOKEN}) {
            AddonException e = fails(auth, header);
            assertEquals(HttpStatus.UNAUTHORIZED, e.status(), String.valueOf(header));
            assertEquals("unauthorized", e.code());
        }
    }

    @Test
    void noTokenOrATooShortTokenSwitchesTheSurfaceOffEvenForAMatchingHeader() {
        for (String configured : new String[] {null, "", "   ", "short", "k".repeat(AddonHostAuth.MIN_LENGTH - 1)}) {
            AddonHostAuth auth = new AddonHostAuth(configured);
            assertFalse(auth.configured(), String.valueOf(configured));
            AddonException e = fails(auth, "Bearer " + configured);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.status());
            assertEquals("host_bridge_disabled", e.code());
        }
    }

    @Test
    void theTokenIsNeverInTheErrorMessage() {
        AddonHostAuth auth = new AddonHostAuth(TOKEN);
        assertFalse(fails(auth, "Bearer nope").getMessage().contains(TOKEN));
    }

    @Test
    void theFingerprintHashIsHexSha256() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", AddonHostAuth.sha256Hex(""));
    }
}
