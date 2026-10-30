package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.agents.turn.ToolLedger;
import com.agenticai.core.approval.ApprovalRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The bracketed approval-outcome note: its record shape and when it reaches conversation history.
 */
class ApprovalOutcomeNotesTest {

    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final Logger LOG = LoggerFactory.getLogger(ApprovalOutcomeNotesTest.class);

    private static ApprovalRequest request() {
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(REQUEST_ID);
        return request;
    }

    private static ReplayTarget target(String sessionId, String memorySessionId) {
        return new ReplayTarget(
                AGENT_ID,
                sessionId,
                "send_file",
                "{\"fileId\":\"f-1\"}",
                "t",
                "alice",
                null,
                memorySessionId);
    }

    @Test
    void truncateCutsLongTextWithTheMarker() {
        assertEquals("", ApprovalOutcomeNotes.truncate(null, 10));
        assertEquals("short", ApprovalOutcomeNotes.truncate("short", 10));
        String cut = ApprovalOutcomeNotes.truncate("0123456789abcdef", 10);
        assertEquals("0123456789" + ApprovalOutcomeNotes.TRUNCATED_SUFFIX, cut);
    }

    @Test
    void outcomeRecordWrapsTheDecisionInTheConfiguredNote() {
        ApprovalContinuationProperties texts = new ApprovalContinuationProperties();
        ApprovalRequest request = request();
        ReplayTarget target = target("s-1", "c1:m");

        ToolLedger ok =
                ApprovalOutcomeNotes.outcomeRecord(
                        texts, request, target, ToolLedger.Status.OK, "admin@x.com", "file sent");
        assertEquals(1, ok.entries().size());
        ToolLedger.Entry entry = ok.entries().get(0);
        assertEquals("send_file", entry.tool());
        assertEquals(ToolLedger.Status.OK, entry.status());
        assertEquals(
                texts.renderCompletedNote("send_file", "file sent"),
                entry.result(),
                "the note is the result the model reads on later turns");
        assertEquals(texts.renderNoteApproval(REQUEST_ID, "admin@x.com"), entry.approval());

        ToolLedger denied =
                ApprovalOutcomeNotes.outcomeRecord(
                        texts,
                        request,
                        target,
                        ToolLedger.Status.NOT_RUN,
                        "admin@x.com",
                        "too risky");
        assertEquals(
                texts.renderDeniedNote("send_file", "admin@x.com", "too risky"),
                denied.entries().get(0).result());

        ToolLedger failed =
                ApprovalOutcomeNotes.outcomeRecord(
                        texts,
                        request,
                        target,
                        ToolLedger.Status.FAILED,
                        "admin@x.com",
                        "smtp down");
        assertEquals(
                texts.renderFailedNote("send_file", "smtp down"), failed.entries().get(0).result());
    }

    @Test
    void appendSkipsSyntheticAndMissingSessions() {
        ObjectProvider<SessionMemoryService> provider = uncheckedProvider();
        SessionMemoryService memory = mock(SessionMemoryService.class);
        when(provider.getIfAvailable()).thenReturn(memory);
        ToolLedger record = ToolLedger.EMPTY;

        assertNull(
                ApprovalOutcomeNotes.append(
                        provider, request(), target(null, "c1:m"), record, LOG));
        assertNull(
                ApprovalOutcomeNotes.append(provider, request(), target(" ", "c1:m"), record, LOG));
        assertNull(
                ApprovalOutcomeNotes.append(
                        provider,
                        request(),
                        target(ReplayTarget.FALLBACK_SESSION_PREFIX + REQUEST_ID, "c1:m"),
                        record,
                        LOG));
        verify(memory, never()).appendTurn(anyString(), any());
    }

    @Test
    void appendIsQuietWhenNoMemoryServiceIsAvailable() {
        ObjectProvider<SessionMemoryService> provider = uncheckedProvider();
        when(provider.getIfAvailable()).thenReturn(null);
        assertNull(
                ApprovalOutcomeNotes.append(
                        provider, request(), target("s-1", "c1:m"), ToolLedger.EMPTY, LOG));
    }

    @Test
    void appendReportsWhenNoScopedIdCanBeIdentified() {
        ObjectProvider<SessionMemoryService> provider = uncheckedProvider();
        SessionMemoryService memory = mock(SessionMemoryService.class);
        when(provider.getIfAvailable()).thenReturn(memory);
        // A real session but no memorySessionId and no tenant/subject to derive one from.
        ReplayTarget target = new ReplayTarget(AGENT_ID, "s-1", "t", "{}", null, null, null, null);
        assertEquals(
                "no conversation history could be identified",
                ApprovalOutcomeNotes.append(provider, request(), target, ToolLedger.EMPTY, LOG));
        verify(memory, never()).appendTurn(anyString(), any());
    }

    @Test
    void appendWritesTheRecordUnderTheScopedId() {
        ObjectProvider<SessionMemoryService> provider = uncheckedProvider();
        SessionMemoryService memory = mock(SessionMemoryService.class);
        when(provider.getIfAvailable()).thenReturn(memory);
        ToolLedger record =
                new ToolLedger(
                        java.util.List.of(
                                new ToolLedger.Entry("t", "{}", "ok", ToolLedger.Status.OK, null)),
                        0);
        assertNull(
                ApprovalOutcomeNotes.append(
                        provider, request(), target("s-1", "c1:scoped"), record, LOG));
        verify(memory).appendTurn(eq("c1:scoped"), any(ChatTurn.class));
    }

    @Test
    void appendReportsAWriteFailureWithoutThrowing() {
        ObjectProvider<SessionMemoryService> provider = uncheckedProvider();
        SessionMemoryService memory = mock(SessionMemoryService.class);
        when(provider.getIfAvailable()).thenReturn(memory);
        doThrow(new IllegalStateException("store down"))
                .when(memory)
                .appendTurn(anyString(), any(ChatTurn.class));
        String why =
                ApprovalOutcomeNotes.append(
                        provider, request(), target("s-1", "c1:scoped"), ToolLedger.EMPTY, LOG);
        assertTrue(why.startsWith("writing to conversation history failed:"), why);
        assertTrue(why.contains("store down"), why);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SessionMemoryService> uncheckedProvider() {
        return mock(ObjectProvider.class);
    }
}
