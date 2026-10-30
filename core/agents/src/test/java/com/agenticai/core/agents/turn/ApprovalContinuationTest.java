package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ApprovalContinuation} carries everything the continuation turn needs that cannot be
 * derived from the continuation message itself. These pin the list normalisation (null to empty,
 * defensive copies), the {@code withGoal} copy, and {@link
 * ApprovalContinuation#isContinuationMessage} - the marker check the transcript uses to tell a
 * platform-written turn from a typed one.
 */
class ApprovalContinuationTest {

    private static ApprovalContinuation continuation() {
        return new ApprovalContinuation(
                UUID.randomUUID(),
                ApprovalContinuation.Outcome.COMPLETED,
                "send_email",
                "{\"to\":\"a@b.c\"}",
                "sent",
                "reviewer@x",
                "the original goal",
                UUID.randomUUID(),
                "session-1",
                "subject-key",
                "tenant-1",
                "user-sub",
                "mem-session",
                List.of("web_search"),
                List.of("another pending action"),
                List.of("send_email"));
    }

    @Test
    void theRecordCarriesEveryField() {
        ApprovalContinuation c = continuation();

        assertEquals(ApprovalContinuation.Outcome.COMPLETED, c.outcome());
        assertEquals("send_email", c.toolName());
        assertEquals("{\"to\":\"a@b.c\"}", c.argumentsJson());
        assertEquals("sent", c.result());
        assertEquals("reviewer@x", c.decidedBy());
        assertEquals("the original goal", c.goal());
        assertEquals("session-1", c.sessionId());
        assertEquals("subject-key", c.subjectKey());
        assertEquals("tenant-1", c.tenantId());
        assertEquals("user-sub", c.subject());
        assertEquals("mem-session", c.memorySessionId());
        assertEquals(List.of("web_search"), c.stickyTools());
        assertEquals(List.of("another pending action"), c.otherPending());
        assertEquals(List.of("send_email"), c.runToolNames());
    }

    @Test
    void nullListsNormaliseToEmptyAndInputsAreCopied() {
        ApprovalContinuation c =
                new ApprovalContinuation(
                        UUID.randomUUID(),
                        ApprovalContinuation.Outcome.DENIED,
                        "t",
                        null,
                        null,
                        null,
                        "g",
                        UUID.randomUUID(),
                        "s",
                        "k",
                        "t",
                        "u",
                        "m",
                        null,
                        null,
                        null);

        assertEquals(List.of(), c.stickyTools());
        assertEquals(List.of(), c.otherPending());
        assertNull(c.runToolNames(), "null stays null - the parked turn was not narrowed");

        java.util.ArrayList<String> sticky = new java.util.ArrayList<>(List.of("a"));
        ApprovalContinuation copied =
                new ApprovalContinuation(
                        UUID.randomUUID(),
                        ApprovalContinuation.Outcome.FAILED,
                        "t",
                        null,
                        null,
                        null,
                        "g",
                        UUID.randomUUID(),
                        "s",
                        "k",
                        "t",
                        "u",
                        "m",
                        sticky,
                        null,
                        null);
        sticky.add("b");
        assertEquals(List.of("a"), copied.stickyTools());
        assertThrows(UnsupportedOperationException.class, () -> copied.stickyTools().add("x"));
    }

    @Test
    void withGoalKeepsEverythingButTheGoal() {
        ApprovalContinuation c = continuation();
        ApprovalContinuation rewritten = c.withGoal("the job goal, extended");

        assertEquals("the job goal, extended", rewritten.goal());
        assertEquals(c.requestId(), rewritten.requestId());
        assertEquals(c.toolName(), rewritten.toolName());
        assertEquals(c.stickyTools(), rewritten.stickyTools());
        assertEquals(
                "the original goal", c.goal(), "the record is immutable - a copy, not a rewrite");
    }

    @Test
    void theShortConstructorLeavesRunToolNamesUnset() {
        ApprovalContinuation c =
                new ApprovalContinuation(
                        UUID.randomUUID(),
                        ApprovalContinuation.Outcome.COMPLETED,
                        "t",
                        null,
                        "r",
                        "d",
                        "g",
                        UUID.randomUUID(),
                        "s",
                        "k",
                        "t",
                        "u",
                        "m",
                        List.of(),
                        List.of());

        assertNull(c.runToolNames());
    }

    @Test
    void isContinuationMessageRecognisesTheMarker() {
        assertTrue(
                ApprovalContinuation.isContinuationMessage(
                        "<approval_result request_id=\"r\" tool=\"t\" outcome=\"COMPLETED\">..."));
        assertFalse(ApprovalContinuation.isContinuationMessage("an ordinary user message"));
        assertFalse(ApprovalContinuation.isContinuationMessage(null));
        assertFalse(
                ApprovalContinuation.isContinuationMessage(" <approval_result"),
                "the marker must open the message - leading whitespace is a different text");
        assertEquals("<approval_result", ApprovalContinuation.MESSAGE_MARKER);
    }
}
