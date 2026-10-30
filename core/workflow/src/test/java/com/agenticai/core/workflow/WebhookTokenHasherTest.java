package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link WebhookTokenHasher}: deterministic lowercase SHA-256 hex digest. */
class WebhookTokenHasherTest {

    @Test
    void sha256HexIsDeterministicAndMatchesKnownVector() {
        String a = WebhookTokenHasher.sha256Hex("tok");
        assertEquals(64, a.length());
        assertEquals(a, WebhookTokenHasher.sha256Hex("tok"));
        assertNotEquals(a, WebhookTokenHasher.sha256Hex("other"));
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                WebhookTokenHasher.sha256Hex(""));
    }

    @Test
    void nullPlaintextThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> WebhookTokenHasher.sha256Hex(null));
    }
}
