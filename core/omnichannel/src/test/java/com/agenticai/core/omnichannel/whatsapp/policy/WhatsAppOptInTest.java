package com.agenticai.core.omnichannel.whatsapp.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** {@link WhatsAppOptIn} and {@link WhatsAppRecipientBackoff}: the two consent/backoff rows. */
class WhatsAppOptInTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void optInStartsActiveWithAnAssignedId() {
        WhatsAppOptIn optIn =
                new WhatsAppOptIn("15551234567", "CHECKOUT", "order #42", T0, "system");

        assertNotNull(optIn.getId());
        assertEquals("15551234567", optIn.getPhoneDigits());
        assertEquals("CHECKOUT", optIn.getSource());
        assertEquals(T0, optIn.getConsentedAt());
        assertNull(optIn.getRevokedAt());
        assertTrue(optIn.isActive());
    }

    @Test
    void recordRefreshesConsentAndClearsAnyRevocation() {
        WhatsAppOptIn optIn = new WhatsAppOptIn("15551234567", "CHECKOUT", "e1", T0, "system");
        Instant t1 = Instant.parse("2026-02-01T00:00:00Z");
        optIn.revoke(t1);
        assertFalse(optIn.isActive());

        Instant t2 = Instant.parse("2026-03-01T00:00:00Z");
        optIn.record("IMPORT", "csv row 7", t2, "admin@x.test");

        assertTrue(optIn.isActive());
        assertNull(optIn.getRevokedAt());
        assertEquals("IMPORT", optIn.getSource());
        assertEquals(t2, optIn.getConsentedAt());
    }

    @Test
    void backoffBlocksTemporarilyUntilTheDeadlinePasses() {
        WhatsAppRecipientBackoff backoff = new WhatsAppRecipientBackoff("15551234567");
        assertFalse(backoff.blocksAt(T0));
        assertFalse(backoff.isPermanent());

        Instant until = Instant.parse("2026-01-01T01:00:00Z");
        backoff.blockUntil(until, 131049, T0);

        assertTrue(backoff.blocksAt(T0));
        assertTrue(backoff.blocksAt(until.minusSeconds(1)));
        assertFalse(backoff.blocksAt(until));
        assertFalse(backoff.blocksAt(until.plusSeconds(1)));
        assertEquals(until, backoff.getBlockedUntil());
        assertEquals(131049, backoff.getMetaCode());
    }

    @Test
    void backoffBlocksPermanentlyUntilCleared() {
        WhatsAppRecipientBackoff backoff = new WhatsAppRecipientBackoff("15551234567");
        backoff.blockPermanently(132015, T0);

        assertTrue(backoff.isPermanent());
        assertNull(backoff.getBlockedUntil());
        assertTrue(backoff.blocksAt(Instant.parse("2036-01-01T00:00:00Z")));
        assertEquals(132015, backoff.getMetaCode());
    }
}
