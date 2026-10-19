package com.agenticai.core.agents.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.StepJournal;
import com.agenticai.core.agents.turn.AgentChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * L1: durable delegate_parallel branches - a COMPLETED branch is replayed from the journal, not
 * re-run.
 */
class DelegateToTeamJournalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JOB_SESSION = "job:" + UUID.randomUUID();

    private final AgentChatService chat = mock(AgentChatService.class);
    private final StepJournal journal = mock(StepJournal.class);
    private final UUID aId = UUID.randomUUID();
    private final UUID bId = UUID.randomUUID();

    private DelegateToTeamToolCallback team(String sessionId) {
        return new DelegateToTeamToolCallback(
                chat,
                List.of(
                        new DelegateToTeamToolCallback.Member(aId, "Researcher", "researcher"),
                        new DelegateToTeamToolCallback.Member(bId, "Writer", "writer")),
                null,
                null,
                journal,
                sessionId);
    }

    @Test
    void aCompletedBranchIsServedFromTheJournalAndOnlyTheRestRuns() throws Exception {
        when(journal.isEnabled()).thenReturn(true);
        when(journal.isResumeEnabled()).thenReturn(true);
        String doneStep =
                "swarm:" + DelegateToTeamToolCallback.branchHash("researcher", "find facts", null);
        when(journal.findCompletedResult(eq(JOB_SESSION), eq(doneStep)))
                .thenReturn(Optional.of("cached A"));
        when(journal.findCompletedResult(
                        eq(JOB_SESSION),
                        org.mockito.ArgumentMatchers.argThat(s -> !doneStep.equals(s))))
                .thenReturn(Optional.empty());
        when(chat.delegateCall(eq(bId), any(), any(), any())).thenReturn("fresh B");

        String out =
                team(JOB_SESSION)
                        .call(
                                "{\"tasks\":["
                                        + "{\"agent\":\"researcher\",\"question\":\"find facts\"},"
                                        + "{\"agent\":\"writer\",\"question\":\"draft it\"}]}");

        JsonNode results = MAPPER.readTree(out).get("results");
        assertEquals("cached A", results.get(0).get("answer").asText());
        assertTrue(results.get(0).get("resumedFromJournal").asBoolean());
        assertEquals("fresh B", results.get(1).get("answer").asText());
        verify(chat, never()).delegateCall(eq(aId), any(), any(), any());
        verify(chat, times(1)).delegateCall(eq(bId), any(), any(), any());
        // The re-run branch is journaled as completed for the next resume.
        verify(journal)
                .complete(eq(JOB_SESSION), anyString(), eq(StepJournal.KIND_SWARM), eq("fresh B"));
    }

    @Test
    void nonJobSessionsOrADisabledJournalBehaveExactlyAsBefore() {
        when(journal.isEnabled()).thenReturn(true);
        when(journal.isResumeEnabled()).thenReturn(true);
        when(chat.delegateCall(any(), any(), any(), any())).thenReturn("x");
        team("console-session")
                .call(
                        "{\"tasks\":[{\"agent\":\"researcher\",\"question\":\"q\"},"
                                + "{\"agent\":\"writer\",\"question\":\"q2\"}]}");
        verify(journal, never()).findCompletedResult(anyString(), anyString());

        // Journal on but resume-from-journal OFF: observability only, so no branch is replayed or
        // journaled.
        StepJournal observeOnly = mock(StepJournal.class);
        when(observeOnly.isEnabled()).thenReturn(true);
        when(observeOnly.isResumeEnabled()).thenReturn(false);
        new DelegateToTeamToolCallback(
                        chat,
                        List.of(
                                new DelegateToTeamToolCallback.Member(
                                        aId, "Researcher", "researcher"),
                                new DelegateToTeamToolCallback.Member(bId, "Writer", "writer")),
                        null,
                        null,
                        observeOnly,
                        JOB_SESSION)
                .call("{\"tasks\":[{\"agent\":\"researcher\",\"question\":\"q\"}]}");
        verify(observeOnly, never()).findCompletedResult(anyString(), anyString());
        verify(observeOnly, never()).complete(anyString(), anyString(), anyString(), any());

        StepJournal off = mock(StepJournal.class);
        when(off.isEnabled()).thenReturn(false);
        new DelegateToTeamToolCallback(
                        chat,
                        List.of(
                                new DelegateToTeamToolCallback.Member(
                                        aId, "Researcher", "researcher"),
                                new DelegateToTeamToolCallback.Member(bId, "Writer", "writer")),
                        null,
                        null,
                        off,
                        JOB_SESSION)
                .call("{\"tasks\":[{\"agent\":\"researcher\",\"question\":\"q\"}]}");
        verify(off, never()).findCompletedResult(anyString(), anyString());
    }
}
