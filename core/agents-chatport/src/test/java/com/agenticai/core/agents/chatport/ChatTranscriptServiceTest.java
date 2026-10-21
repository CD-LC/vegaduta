package com.agenticai.core.agents.chatport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.branching.ChatBranchDtos.ActivePathResponse;
import com.agenticai.core.agents.branching.ChatBranchDtos.MessageView;
import com.agenticai.core.agents.branching.ChatBranchService;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.InMemorySessionMemory;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The reload endpoint: a failed turn comes back as an assistant message flagged failed. */
class ChatTranscriptServiceTest {

    private final UUID agentId = UUID.randomUUID();
    private final AgentService agentService = mock(AgentService.class);
    private final SessionMemoryService sessions = mock(SessionMemoryService.class);
    private final ChatBranchService branchService = mock(ChatBranchService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChatBranchService> branches = mock(ObjectProvider.class);

    private ChatTranscriptService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("t1", "alice", Set.of("tenant_user")));
        when(branches.getIfAvailable()).thenReturn(branchService);
        service = new ChatTranscriptService(agentService, sessions, branches);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** SEC-004: the id chat stored alice's turns on {@code sid} under (AgentChatService#chat). */
    private String mine(String sid) {
        return SessionMemoryService.scopedSessionId("t1", agentId, "alice", sid);
    }

    @Test
    void aFailedTurnReloadsAsTheUserMessageAndAnAssistantErrorMessage() {
        when(sessions.isOwnedBy(mine("s1"), "t1", "alice")).thenReturn(true);
        when(sessions.getFullHistory(mine("s1")))
                .thenReturn(
                        List.of(
                                ChatTurn.user("Say ok."),
                                ChatTurn.failedAssistant(
                                        "The AI provider rejected our credentials. Check the API"
                                                + " key.")));

        ChatTranscriptService.Transcript transcript = service.transcript(agentId, "s1");

        assertEquals(2, transcript.messages().size());
        assertEquals("user", transcript.messages().get(0).role());
        assertEquals("ok", transcript.messages().get(0).status());
        var failed = transcript.messages().get(1);
        assertEquals("assistant", failed.role());
        assertEquals("failed", failed.status());
        assertTrue(failed.error());
        assertEquals(
                "The AI provider rejected our credentials. Check the API key.", failed.content());
        assertFalse(failed.content().contains("[reply failed]"));
        verify(agentService).requireAccess(agentId, PermissionLevel.RUNNER);
    }

    @Test
    void aSessionTheCallerDidNotStartIsNotFoundAndNeverRead() {
        when(sessions.isOwnedBy(mine("s1"), "t1", "alice")).thenReturn(false);

        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class, () -> service.transcript(agentId, "s1"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(sessions, never()).getFullHistory("s1");
    }

    @Test
    void anExpiredOrEmptySessionIsNotFound() {
        when(sessions.isOwnedBy(mine("s1"), "t1", "alice")).thenReturn(true);
        when(sessions.getFullHistory(mine("s1"))).thenReturn(List.of());

        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.transcript(agentId, "s1"))
                        .getStatusCode());
    }

    // ---- the stored tree as the fallback once Redis has let the session go ----

    private static MessageView view(String role, String content, String status) {
        return new MessageView(
                UUID.randomUUID(), null, role, content, Instant.EPOCH, 0, 1, status, "failed".equals(status));
    }

    @Test
    void anExpiredSessionComesBackFromTheStoredTreeWhenBranchingIsOn() {
        // Owner record gone with the list: the live read says "not yours", the tree says otherwise.
        when(sessions.isOwnedBy(mine("s1"), "t1", "alice")).thenReturn(false);
        when(branchService.isEnabled()).thenReturn(true);
        when(branchService.activePath(agentId, "s1"))
                .thenReturn(
                        new ActivePathResponse(
                                "s1",
                                null,
                                List.of(view("user", "Say ok.", "ok"), view("assistant", "Key rejected.", "failed"))));

        ChatTranscriptService.Transcript transcript = service.transcript(agentId, "s1");

        assertEquals(2, transcript.messages().size());
        assertEquals("user", transcript.messages().get(0).role());
        assertEquals("Say ok.", transcript.messages().get(0).content());
        assertTrue(transcript.messages().get(1).error());
        assertEquals("failed", transcript.messages().get(1).status());
        verify(sessions, never()).getFullHistory(mine("s1"));
    }

    @Test
    void theLiveListWinsOverTheTreeWhileItIsStillThere() {
        when(sessions.isOwnedBy(mine("s1"), "t1", "alice")).thenReturn(true);
        when(sessions.getFullHistory(mine("s1"))).thenReturn(List.of(ChatTurn.user("live")));
        when(branchService.isEnabled()).thenReturn(true);

        assertEquals("live", service.transcript(agentId, "s1").messages().get(0).content());
        verify(branchService, never()).activePath(any(), any());
    }

    @Test
    void aTreeBoundToSomeoneElseOrAnEmptyOneIsStillNotFound() {
        when(sessions.isOwnedBy(mine("s1"), "t1", "alice")).thenReturn(false);
        when(branchService.isEnabled()).thenReturn(true);
        when(branchService.activePath(agentId, "s1"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found."));
        when(branchService.activePath(agentId, "s2"))
                .thenReturn(new ActivePathResponse("s2", null, List.of()));

        for (String sid : List.of("s1", "s2")) {
            ResponseStatusException e =
                    assertThrows(ResponseStatusException.class, () -> service.transcript(agentId, sid));
            assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
            assertEquals(ChatTranscriptService.NO_SESSION_MESSAGE, e.getReason());
        }
    }

    @Test
    void withBranchingOffOrAbsentTheTreeIsNeverAsked() {
        when(sessions.isOwnedBy(mine("s1"), "t1", "alice")).thenReturn(true);
        when(sessions.getFullHistory(mine("s1"))).thenReturn(List.of());
        when(branchService.isEnabled()).thenReturn(false);
        assertThrows(ResponseStatusException.class, () -> service.transcript(agentId, "s1"));

        when(branches.getIfAvailable()).thenReturn(null);
        assertThrows(ResponseStatusException.class, () -> service.transcript(agentId, "s1"));

        verify(branchService, never()).activePath(any(), any());
    }

    @Test
    void aBadSessionIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.transcript(agentId, " "));
        assertThrows(
                IllegalArgumentException.class, () -> service.transcript(agentId, "a".repeat(201)));
    }

    // ---- against the real SEC-004 key derivation, not a mock that accepts any id ----

    /** What {@code AgentChatService#chat} does for an authenticated caller's turn. */
    private static void chatAs(
            SessionMemoryService memory, String subject, UUID agent, String sid, String text) {
        TenantContext.set(TenantContext.of("t1", subject, Set.of("tenant_user")));
        String scoped = SessionMemoryService.scopedSessionId("t1", agent, subject, sid);
        memory.appendTurn(scoped, ChatTurn.user(text));
    }

    @Test
    void theOwnerReloadsTheSessionTheyChattedIn() {
        InMemorySessionMemory redis = new InMemorySessionMemory();
        chatAs(redis.service(), "alice", agentId, "s1", "Hello from alice");
        ChatTranscriptService real = new ChatTranscriptService(agentService, redis.service(), branches);

        ChatTranscriptService.Transcript transcript = real.transcript(agentId, "s1");

        assertEquals("s1", transcript.sessionId());
        assertEquals(1, transcript.messages().size());
        assertEquals("Hello from alice", transcript.messages().get(0).content());
    }

    @Test
    void anotherMemberOfTheTenantCannotReadItWithTheSameClientId() {
        InMemorySessionMemory redis = new InMemorySessionMemory();
        chatAs(redis.service(), "alice", agentId, "s1", "Hello from alice");
        TenantContext.set(TenantContext.of("t1", "bob", Set.of("tenant_user")));
        ChatTranscriptService real = new ChatTranscriptService(agentService, redis.service(), branches);

        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> real.transcript(agentId, "s1"))
                        .getStatusCode());
    }

    @Test
    void twoMembersReusingOneClientIdEachReadOnlyTheirOwn() {
        InMemorySessionMemory redis = new InMemorySessionMemory();
        chatAs(redis.service(), "alice", agentId, "s1", "alice's turn");
        chatAs(redis.service(), "bob", agentId, "s1", "bob's turn");
        ChatTranscriptService real = new ChatTranscriptService(agentService, redis.service(), branches);

        assertEquals("bob's turn", real.transcript(agentId, "s1").messages().get(0).content());
        TenantContext.set(TenantContext.of("t1", "alice", Set.of("tenant_user")));
        assertEquals("alice's turn", real.transcript(agentId, "s1").messages().get(0).content());
    }
}
