package com.agenticai.core.omnichannel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TelegramWebhookSecretTest {

    @Test
    void theDigestIsTheKnownSha256AndFitsTheColumn() {
        // sha256("abc"): FIPS 180-2 test vector.
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                TelegramWebhookSecret.hash("abc"));
        assertEquals(64, TelegramWebhookSecret.hash("any secret").length());
    }

    @Test
    void theRawSecretMatchesItsDigestAndNothingElseDoes() {
        String stored = TelegramWebhookSecret.hash("secret-1");

        assertTrue(TelegramWebhookSecret.matches("secret-1", stored));
        assertFalse(TelegramWebhookSecret.matches("secret-2", stored));
        assertFalse(TelegramWebhookSecret.matches(stored, stored));
        assertFalse(TelegramWebhookSecret.matches(null, stored));
        assertFalse(TelegramWebhookSecret.matches("secret-1", null));
        assertNotEquals("secret-1", stored);
    }
}
