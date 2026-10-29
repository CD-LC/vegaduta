package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link TurnReplyOutcome} is the one mutable member of {@link TurnContext}: how the reply of ONE
 * turn ended. These pin that only {@code COMPLETE} counts as an answer (cacheable, storable,
 * judgeable), that {@code begin} resets the attempt, and that {@code snapshot}/{@code restore}
 * round-trips a state a later attempt must not lose.
 */
class TurnReplyOutcomeTest {

    @Test
    void aFreshOutcomeIsComplete() {
        TurnReplyOutcome outcome = new TurnReplyOutcome();

        assertTrue(outcome.isComplete());
        assertEquals(TurnReplyOutcome.Kind.COMPLETE, outcome.kind());
        assertNull(outcome.detail());
        assertNull(outcome.replacement());
        assertFalse(outcome.isHeldForOutputCheck());
    }

    @Test
    void nonCompleteKindsAreNotAnswers() {
        TurnReplyOutcome outcome = new TurnReplyOutcome();

        for (TurnReplyOutcome.Kind kind : new TurnReplyOutcome.Kind[] {
                TurnReplyOutcome.Kind.TRUNCATED,
                TurnReplyOutcome.Kind.REFUSED,
                TurnReplyOutcome.Kind.MALFORMED_TOOL_CALL,
                TurnReplyOutcome.Kind.EMPTY,
                TurnReplyOutcome.Kind.INTERRUPTED}) {
            outcome.mark(kind);
            assertFalse(outcome.isComplete(), kind.name());
            assertEquals(kind, outcome.kind());
        }
    }

    @Test
    void markCarriesTheProvidersFinishReason() {
        TurnReplyOutcome outcome = new TurnReplyOutcome();

        outcome.mark(TurnReplyOutcome.Kind.REFUSED, "content_filter");

        assertEquals(TurnReplyOutcome.Kind.REFUSED, outcome.kind());
        assertEquals("content_filter", outcome.detail());
        // A null kind or COMPLETE collapses back to the plain complete state.
        outcome.mark(null);
        assertTrue(outcome.isComplete());
        outcome.mark(TurnReplyOutcome.Kind.COMPLETE, "ignored");
        assertTrue(outcome.isComplete());
        assertNull(outcome.detail());
    }

    @Test
    void beginStartsAFreshAttempt() {
        TurnReplyOutcome outcome = new TurnReplyOutcome();
        outcome.mark(TurnReplyOutcome.Kind.INTERRUPTED);
        outcome.replaceWith("fallback text");

        outcome.begin();

        assertTrue(outcome.isComplete());
        assertNull(outcome.replacement());
    }

    @Test
    void aHeldReplyCanBeReplacedForThePerson() {
        TurnReplyOutcome outcome = new TurnReplyOutcome();

        outcome.holdForOutputCheck(true);
        assertTrue(outcome.isHeldForOutputCheck());
        outcome.replaceWith("[reply withheld by guardrails]");
        assertEquals("[reply withheld by guardrails]", outcome.replacement());

        outcome.holdForOutputCheck(false);
        assertFalse(outcome.isHeldForOutputCheck());
    }

    @Test
    void snapshotAndRestoreRoundTripTheAttemptState() {
        TurnReplyOutcome outcome = new TurnReplyOutcome();
        outcome.mark(TurnReplyOutcome.Kind.TRUNCATED, "max_tokens");

        TurnReplyOutcome.State saved = outcome.snapshot();
        outcome.mark(TurnReplyOutcome.Kind.COMPLETE);
        assertTrue(outcome.isComplete());

        outcome.restore(saved);
        assertEquals(TurnReplyOutcome.Kind.TRUNCATED, outcome.kind());
        assertEquals("max_tokens", outcome.detail());

        // A null snapshot restores the plain complete state.
        outcome.restore(null);
        assertTrue(outcome.isComplete());
    }
}
