package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What a person reloading a conversation is shown for each kind of stored turn. */
class TranscriptViewTest {

    @Test
    void aFailedReplyIsMarkedFailedAndShowsTheMessageNotTheTechnicalPrefix() {
        TranscriptView.Entry e = TranscriptView.of(ChatTurn.failedAssistant("The AI provider rejected our credentials."));

        assertEquals("assistant", e.role());
        assertEquals("failed", e.status());
        assertTrue(e.error());
        assertEquals("The AI provider rejected our credentials.", e.content());
        assertFalse(e.content().contains("[reply failed]"));
    }

    @Test
    void anInterruptedReplyKeepsItsPartialTextWithoutTheMarker() {
        TranscriptView.Entry e = TranscriptView.of(ChatTurn.assistant("The answer is" + ChatTurn.INTERRUPTED_REPLY_SUFFIX));

        assertEquals("interrupted", e.status());
        assertFalse(e.error());
        assertEquals("The answer is", e.content());
    }

    @Test
    void ordinaryMessagesAreOkAndUnchanged() {
        TranscriptView.Entry user = TranscriptView.of(ChatTurn.user("Say ok."));
        TranscriptView.Entry assistant = TranscriptView.of(ChatTurn.assistant("ok"));

        assertEquals("ok", user.status());
        assertEquals("user", user.role());
        assertEquals("Say ok.", user.content());
        assertEquals("ok", assistant.status());
        assertFalse(assistant.error());
    }

    @Test
    void aUserWhoTypesTheMarkerIsNotMistakenForAFailedReply() {
        TranscriptView.Entry e = TranscriptView.of(ChatTurn.user(ChatTurn.FAILED_REPLY_PREFIX + "ha"));

        assertEquals("ok", e.status());
        assertEquals(ChatTurn.FAILED_REPLY_PREFIX + "ha", e.content());
    }
}
