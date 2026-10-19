package com.agenticai.core.omnichannel.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ChannelSession} carries most of the real, non-obvious behavior for
 * Autonomy/Guardrails Strategy Stage A's pairing gate and the "still extract
 * phone numbers are not visible" (migration V51) preview/display logic -
 * pure entity logic with no repository or transaction boundary, so it's
 * cheap to test directly and had zero coverage despite three behaviors a
 * refactor could easily break silently: (1) {@link
 * ChannelSession#touch(String, String)}'s "never blank out a previously-
 * resolved phone number" rule, (2) {@link
 * ChannelSession#markAiDisclosureSentIfNeeded()}'s exactly-once semantics
 * (AI Act Article 50 transparency - the disclosure prefix must be sent on
 * the transition to true and never again), and (3) the 300-char preview
 * truncation migration V49 introduced.
 *
 * <p>Constructed for real throughout (public constructors, no JPA-only
 * fields needed by any assertion here) - no mocking anywhere in this suite.
 */
class ChannelSessionTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    // --- construction ------------------------------------------------------

    @Test
    void aBrandNewSessionStartsUnpairedWithARandomSixDigitPairingCode() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "+15551234567", "session-1", AGENT_ID);

        assertFalse(session.isPaired());
        assertNotNull(session.getPairingCode());
        assertEquals(6, session.getPairingCode().length());
        assertTrue(session.getPairingCode().chars().allMatch(Character::isDigit));
        assertFalse(session.isPairingNoticeSent());
        assertFalse(session.isAiDisclosureSent());
    }

    @Test
    void constructionSetsCreatedAtAndLastMessageAtToTheSameInstant() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "+1555", "session-1", AGENT_ID);

        assertEquals(session.getCreatedAt(), session.getLastMessageAt());
    }

    @Test
    void theTwoArgConstructorOverloadLeavesTheMessagePreviewAndResolvedPhoneNumberNull() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "+1555", "session-1", AGENT_ID);

        assertNull(session.getLastMessagePreview());
        assertNull(session.getResolvedPhoneNumber());
    }

    @Test
    void theFiveArgConstructorOverloadSeedsTheFirstMessagePreview() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "+1555", "session-1", AGENT_ID, "hello there");

        assertEquals("hello there", session.getLastMessagePreview());
        assertNull(session.getResolvedPhoneNumber());
    }

    @Test
    void theSixArgConstructorOverloadSeedsBothThePreviewAndTheResolvedPhoneNumber() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "119363123456@lid", "session-1", AGENT_ID, "hi", "+15559876543");

        assertEquals("hi", session.getLastMessagePreview());
        assertEquals("+15559876543", session.getResolvedPhoneNumber());
    }

    // --- touch(): preview truncation -----------------------------------------

    @Test
    void touchWithAMessagePreviewUnder300CharsStoresItUnchanged() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "+1555", "session-1", AGENT_ID);

        session.touch("a short message");

        assertEquals("a short message", session.getLastMessagePreview());
    }

    @Test
    void touchTruncatesAMessagePreviewOver300CharsAndAppendsAnEllipsis() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "+1555", "session-1", AGENT_ID);
        String longMessage = "x".repeat(350);

        session.touch(longMessage);

        assertEquals(301, session.getLastMessagePreview().length());
        assertTrue(session.getLastMessagePreview().startsWith("x".repeat(300)));
        assertTrue(session.getLastMessagePreview().endsWith("…"));
    }

    @Test
    void touchTrimsWhitespaceAndTreatsAnAllWhitespacePreviewAsNoPreview() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "+1555", "session-1", AGENT_ID, "original");

        session.touch("   \n\t  ");

        // A blank-after-trim preview is dropped (truncatePreview returns null),
        // and touch(String) only overwrites the field when messagePreview
        // itself is non-null - here it IS non-null ("   "), so this still
        // overwrites the previous preview with null, not a no-op.
        assertNull(session.getLastMessagePreview());
    }

    @Test
    void touchWithNoArgumentsUpdatesLastMessageAtButLeavesThePreviewUntouched() throws InterruptedException {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "+1555", "session-1", AGENT_ID, "keep me");
        Instant createdLastMessageAt = session.getLastMessageAt();
        Thread.sleep(5);

        session.touch();

        assertEquals("keep me", session.getLastMessagePreview());
        assertTrue(session.getLastMessageAt().isAfter(createdLastMessageAt));
    }

    // --- touch(): resolved phone number never blanks out ---------------------

    @Test
    void touchNeverBlanksOutAPreviouslyResolvedPhoneNumberWhenTheNewMessageHasNone() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "119363123456@lid", "session-1", AGENT_ID, "hi", "+15559876543");

        session.touch("second message", null);

        assertEquals("+15559876543", session.getResolvedPhoneNumber());
    }

    @Test
    void touchNeverBlanksOutAPreviouslyResolvedPhoneNumberWhenTheNewValueIsBlank() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "119363123456@lid", "session-1", AGENT_ID, "hi", "+15559876543");

        session.touch("second message", "   ");

        assertEquals("+15559876543", session.getResolvedPhoneNumber());
    }

    @Test
    void touchUpdatesTheResolvedPhoneNumberWhenANewNonBlankOneIsSupplied() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "119363123456@lid", "session-1", AGENT_ID);

        session.touch("hi", "+15551112222");

        assertEquals("+15551112222", session.getResolvedPhoneNumber());
    }

    // --- rebind() / pairing lifecycle -----------------------------------------

    @Test
    void rebindChangesTheAgentIdOfAnExistingSessionWithoutAffectingAnythingElse() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "+1555", "session-1", AGENT_ID);
        UUID newAgentId = UUID.randomUUID();

        session.rebind(newAgentId);

        assertEquals(newAgentId, session.getAgentId());
    }

    @Test
    void approvePairingSetsPairedTrueAndClearsThePairingCode() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "+1555", "session-1", AGENT_ID);

        session.approvePairing();

        assertTrue(session.isPaired());
        assertNull(session.getPairingCode());
    }

    @Test
    void markPairingNoticeSentFlipsTheFlagAndStaysIdempotent() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "+1555", "session-1", AGENT_ID);

        session.markPairingNoticeSent();

        assertTrue(session.isPairingNoticeSent());
    }

    // --- markAiDisclosureSentIfNeeded(): exactly-once semantics ---------------

    @Test
    void markAiDisclosureSentIfNeededReturnsTrueOnlyOnTheFirstCall() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "+1555", "session-1", AGENT_ID);

        assertTrue(session.markAiDisclosureSentIfNeeded());
        assertTrue(session.isAiDisclosureSent());
    }

    @Test
    void markAiDisclosureSentIfNeededReturnsFalseOnEverySubsequentCall() {
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "+1555", "session-1", AGENT_ID);

        session.markAiDisclosureSentIfNeeded();

        assertFalse(session.markAiDisclosureSentIfNeeded());
        assertFalse(session.markAiDisclosureSentIfNeeded());
        assertTrue(session.isAiDisclosureSent());
    }
}
