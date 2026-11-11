package com.agenticai.core.tenancy.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link DemoChannelLink} state machine: PENDING on issue, single-use pairing code cleared on
 * redeem, {@code linkedAt} first-write-wins, revoke/reissue resetting exactly the fields their
 * javadocs promise, and the monotonically increasing {@code messageCount}.
 */
class DemoChannelLinkTest {

    private static DemoChannelLink newLink() {
        return new DemoChannelLink(
                UUID.randomUUID(), DemoChannelType.TELEGRAM, "AB12CD", Instant.now());
    }

    @Test
    void aFreshLinkIsPendingWithItsPairingCode() {
        DemoChannelLink link = newLink();

        assertEquals(DemoChannelLinkState.PENDING, link.getState());
        assertFalse(link.isLinked());
        assertEquals("AB12CD", link.getPairingCode());
        assertNull(link.getExternalId());
        assertNull(link.getPeerLabel());
        assertNull(link.getLinkedAt());
        assertNull(link.getRevokedAt());
        assertNull(link.getLastMessageAt());
        assertEquals(0, link.getMessageCount());
        assertEquals(DemoChannelType.TELEGRAM, link.getChannel());
        assertEquals("AB12CD", link.getPairingCode());
        assertNotNull(link.getCreatedAt());
        assertNull(link.getId()); // @GeneratedValue - only JPA assigns it
    }

    @Test
    void markLinkedConsumesTheSingleUseCodeAndStampsLinkedAtOnce() {
        DemoChannelLink link = newLink();
        Instant first = Instant.now();
        Instant later = first.plusSeconds(60);

        link.markLinked("chat-42", "Telegram user", first);
        link.markLinked("chat-42", "Telegram user", later); // a re-delivered update

        assertEquals(DemoChannelLinkState.LINKED, link.getState());
        assertTrue(link.isLinked());
        assertNull(link.getPairingCode()); // single-use, cleared on redeem
        assertEquals("chat-42", link.getExternalId());
        assertEquals("Telegram user", link.getPeerLabel());
        assertEquals(first, link.getLinkedAt()); // first-write-wins
    }

    @Test
    void markRevokedClearsTheExternalIdAndCodeButKeepsLinkedAt() {
        DemoChannelLink link = newLink();
        Instant at = Instant.now();
        link.markLinked("chat-42", "peer", at);

        link.markRevoked(at.plusSeconds(120));

        assertEquals(DemoChannelLinkState.REVOKED, link.getState());
        assertFalse(link.isLinked());
        assertNull(link.getExternalId());
        assertNull(link.getPairingCode());
        assertEquals(at, link.getLinkedAt()); // history kept
        assertEquals(at.plusSeconds(120), link.getRevokedAt());
    }

    @Test
    void reissueReArmsARevokedRowWithoutTouchingHistory() {
        DemoChannelLink link = newLink();
        Instant at = Instant.now();
        link.markLinked("chat-42", "peer", at);
        link.recordMessage(at.plusSeconds(1));
        link.markRevoked(at.plusSeconds(120));

        link.reissue("XY99ZZ");

        assertEquals(DemoChannelLinkState.PENDING, link.getState());
        assertEquals("XY99ZZ", link.getPairingCode());
        assertNull(link.getExternalId());
        assertNull(link.getPeerLabel());
        assertNull(link.getRevokedAt());
        // History is deliberately kept: createdAt, linkedAt, messageCount.
        assertEquals(at, link.getLinkedAt());
        assertEquals(1, link.getMessageCount());
    }

    @Test
    void recordMessageCountsAndStampsLastMessageAt() {
        DemoChannelLink link = newLink();
        Instant at = Instant.now();

        link.recordMessage(at);
        link.recordMessage(at.plusSeconds(10));

        assertEquals(2, link.getMessageCount());
        assertEquals(at.plusSeconds(10), link.getLastMessageAt());
    }
}
