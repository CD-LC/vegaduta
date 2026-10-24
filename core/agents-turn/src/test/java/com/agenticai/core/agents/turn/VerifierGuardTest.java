package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * The buffered verifiers rewrite an answer without tools, which left their input (every tool result of the
 * turn) and their output (any text at all) unbounded. {@code app.chat.parity.verifier-*} bounds both.
 */
class VerifierGuardTest {

    private final ChatParityProperties parity = new ChatParityProperties();

    @Test
    void evidenceIsCutToTheNewestCharactersAndShortEvidenceIsLeftAlone() {
        parity.setVerifierMaxEvidenceChars(10);

        assertEquals("0123456789", VerifierGuard.boundEvidence("xxxxxxxxxx0123456789", parity));
        assertEquals("short", VerifierGuard.boundEvidence("short", parity));
        assertNull(VerifierGuard.boundEvidence(null, parity), "no tool ran: nothing to bound");
    }

    @Test
    void zeroMeansNoCutOnEvidence() {
        parity.setVerifierMaxEvidenceChars(0);
        String huge = "e".repeat(100_000);

        assertSame(huge, VerifierGuard.boundEvidence(huge, parity));
    }

    @Test
    void aRewriteThatIsAFractionOfALongAnswerIsNotAccepted() {
        String original = "A".repeat(2_000);

        assertEquals(original, VerifierGuard.acceptRevision(original, "I could not find that.", parity));
    }

    @Test
    void aRewriteOfReasonableSizeIsAccepted() {
        String original = "A".repeat(2_000);
        String revised = "B".repeat(1_500);

        assertEquals(revised, VerifierGuard.acceptRevision(original, revised, parity));
    }

    @Test
    void aShortAnswerMayBeRewrittenToAnythingBecauseTheRatioOnlyGuardsLongAnswers() {
        assertEquals("Ok.", VerifierGuard.acceptRevision("Here is the answer you asked for.", "Ok.", parity));
    }

    @Test
    void theRatioCheckCanBeSwitchedOff() {
        parity.setVerifierMinRevisionRatio(0);
        String original = "A".repeat(2_000);

        assertEquals("x", VerifierGuard.acceptRevision(original, "x", parity));
    }

    @Test
    void anUnchangedOrMissingRewriteIsPassedThrough() {
        assertNull(VerifierGuard.acceptRevision("answer", null, parity));
        assertEquals("answer", VerifierGuard.acceptRevision("answer", "answer", parity));
    }
}
