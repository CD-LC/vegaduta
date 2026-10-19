package com.agenticai.core.agents.resumable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.resumable.ResumableTurnReplyService.PersistedReply;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which stored assistant message counts as THIS turn's reply. */
class ResumableTurnReplyServiceTest {

    private static final long STARTED = 1_700_000_000_000L;

    private static ChatTurn assistantAt(long epochMs, String text) {
        return new ChatTurn(MessageRole.ASSISTANT, text, Instant.ofEpochMilli(epochMs));
    }

    private static ChatTurn userAt(long epochMs, String text) {
        return new ChatTurn(MessageRole.USER, text, Instant.ofEpochMilli(epochMs));
    }

    @Test
    void returnsTheNewestAssistantMessageWrittenAfterTheTurnStarted() {
        PersistedReply reply = ResumableTurnReplyService.pick("turn-1", STARTED, List.of(
            userAt(STARTED - 5_000L, "q1"),
            assistantAt(STARTED - 4_000L, "a1"),
            userAt(STARTED + 10L, "q2"),
            assistantAt(STARTED + 4_000L, "a2")));

        assertEquals(ResumableTurnReplyService.STATUS_COMPLETE, reply.status());
        assertEquals("a2", reply.text());
    }

    @Test
    void anEarlierTurnsReplyIsNeverPassedOffAsThisOne() {
        PersistedReply reply = ResumableTurnReplyService.pick("turn-1", STARTED, List.of(
            userAt(STARTED - 5_000L, "q1"),
            assistantAt(STARTED - 4_000L, "a1"),
            userAt(STARTED + 10L, "q2")));

        assertEquals(ResumableTurnReplyService.STATUS_PENDING, reply.status());
        assertNull(reply.text());
    }

    @Test
    void emptyOrMissingHistoryIsPending() {
        assertEquals(ResumableTurnReplyService.STATUS_PENDING,
            ResumableTurnReplyService.pick("turn-1", STARTED, List.of()).status());
        assertEquals(ResumableTurnReplyService.STATUS_PENDING,
            ResumableTurnReplyService.pick("turn-1", STARTED, null).status());
    }

    @Test
    void aReplyThatFailedWithNoTextIsReportedFailedWithTheMessageNotTheTechnicalPrefix() {
        PersistedReply reply = ResumableTurnReplyService.pick("turn-1", STARTED, List.of(
            userAt(STARTED + 10L, "Say ok."),
            new ChatTurn(MessageRole.ASSISTANT, ChatTurn.FAILED_REPLY_PREFIX + "The AI provider rejected our credentials.",
                Instant.ofEpochMilli(STARTED + 900L))));

        assertEquals(ResumableTurnReplyService.STATUS_FAILED, reply.status());
        assertEquals("The AI provider rejected our credentials.", reply.text());
        org.junit.jupiter.api.Assertions.assertTrue(reply.error());
    }

    @Test
    void anOrdinaryReplyIsNotAnError() {
        PersistedReply reply = ResumableTurnReplyService.pick("turn-1", STARTED, List.of(assistantAt(STARTED + 5L, "hi")));

        assertEquals(ResumableTurnReplyService.STATUS_COMPLETE, reply.status());
        org.junit.jupiter.api.Assertions.assertFalse(reply.error());
    }

    @Test
    void failureMessageComesFromTheStoredTranscriptAndIsNullForATurnThatDidNotFail() {
        ResumableChatStreamService streams = org.mockito.Mockito.mock(ResumableChatStreamService.class);
        com.agenticai.core.agents.memory.SessionMemoryService sessions =
            org.mockito.Mockito.mock(com.agenticai.core.agents.memory.SessionMemoryService.class);
        ResumableStreamBuffer.TurnMeta meta = new ResumableStreamBuffer.TurnMeta(
            "turn-1", "t1", "alice", java.util.UUID.randomUUID().toString(), "s1", STARTED);
        org.mockito.Mockito.when(streams.authorizedMeta("turn-1")).thenReturn(meta);
        ResumableTurnReplyService service = new ResumableTurnReplyService(streams, sessions);

        org.mockito.Mockito.when(sessions.getFullHistory("s1")).thenReturn(List.of(
            userAt(STARTED + 10L, "Say ok."),
            new ChatTurn(MessageRole.ASSISTANT, ChatTurn.FAILED_REPLY_PREFIX + "Rejected.", Instant.ofEpochMilli(STARTED + 900L))));
        assertEquals("Rejected.", service.failureMessage("turn-1"));

        org.mockito.Mockito.when(sessions.getFullHistory("s1")).thenReturn(List.of(
            userAt(STARTED + 10L, "Say ok."), assistantAt(STARTED + 900L, "ok")));
        assertNull(service.failureMessage("turn-1"));
    }
}
