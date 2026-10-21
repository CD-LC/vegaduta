package com.agenticai.core.agents.branching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.branching.ChatBranchStore.Thread;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/**
 * The thread-list side of {@link JpaChatBranchStore}: one page query plus ONE title query for the page, the
 * cursor picking the "before" finder, and a delete that touches nothing unless the row is the subject's own.
 */
class JpaChatBranchStoreThreadsTest {

    private final ChatBranchNodeRepository nodes = mock(ChatBranchNodeRepository.class);
    private final ChatBranchSessionRepository sessions = mock(ChatBranchSessionRepository.class);
    private final JpaChatBranchStore store = new JpaChatBranchStore(nodes, sessions);
    private final UUID agentId = UUID.randomUUID();

    private ChatBranchSession row(String sessionId, String owner) {
        ChatBranchSession row = new ChatBranchSession(agentId, sessionId);
        row.apply(new ChatBranchStore.State(agentId, sessionId, owner, null, null));
        return row;
    }

    @Test
    void firstPageUsesTheUnboundedFinderAndFetchesEveryTitleInOneQuery() {
        ChatBranchSession a = row("a", "alice");
        ChatBranchSession b = row("b", "alice");
        when(sessions.findByOwnerSubjectOrderByUpdatedAtDesc(eq("alice"), any(Pageable.class)))
            .thenReturn(List.of(a, b));
        // Postgres hands the uuid column back as a UUID; "b" has no user message yet.
        when(nodes.findFirstUserMessageOfSessions(anyList()))
            .thenReturn(List.<Object[]>of(new Object[] {agentId, "a", "first question"}));

        List<Thread> page = store.findThreadsOwnedBy("alice", null, 10);

        assertEquals(2, page.size());
        assertEquals("a", page.get(0).sessionId());
        assertEquals("first question", page.get(0).firstUserMessage());
        assertEquals(agentId, page.get(0).agentId());
        assertEquals(a.getCreatedAt(), page.get(0).createdAt());
        assertEquals(a.getUpdatedAt(), page.get(0).updatedAt());
        assertNull(page.get(1).firstUserMessage());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> ids = ArgumentCaptor.forClass(List.class);
        verify(nodes, times(1)).findFirstUserMessageOfSessions(ids.capture());
        assertEquals(List.of(a.getId(), b.getId()), ids.getValue());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(sessions).findByOwnerSubjectOrderByUpdatedAtDesc(eq("alice"), pageable.capture());
        assertEquals(10, pageable.getValue().getPageSize());
        verify(sessions, never()).findByOwnerSubjectAndUpdatedAtBeforeOrderByUpdatedAtDesc(anyString(), any(), any());
    }

    @Test
    void aCursorUsesTheBeforeFinderAndAStringUuidFromTheDriverIsAccepted() {
        Instant before = Instant.parse("2026-10-20T10:00:00Z");
        ChatBranchSession a = row("a", "alice");
        when(sessions.findByOwnerSubjectAndUpdatedAtBeforeOrderByUpdatedAtDesc(eq("alice"), eq(before), any(Pageable.class)))
            .thenReturn(List.of(a));
        when(nodes.findFirstUserMessageOfSessions(List.of(a.getId())))
            .thenReturn(List.<Object[]>of(new Object[] {agentId.toString(), "a", "hello"}));

        List<Thread> page = store.findThreadsOwnedBy("alice", before, 5);

        assertEquals(1, page.size());
        assertEquals("hello", page.get(0).firstUserMessage());
        verify(sessions, never()).findByOwnerSubjectOrderByUpdatedAtDesc(anyString(), any());
    }

    @Test
    void anEmptyPageOrABlankSubjectAsksForNoTitles() {
        when(sessions.findByOwnerSubjectOrderByUpdatedAtDesc(eq("alice"), any(Pageable.class))).thenReturn(List.of());

        assertTrue(store.findThreadsOwnedBy("alice", null, 10).isEmpty());
        assertTrue(store.findThreadsOwnedBy(" ", null, 10).isEmpty());
        assertTrue(store.findThreadsOwnedBy(null, null, 10).isEmpty());
        assertTrue(store.findThreadsOwnedBy("alice", null, 0).isEmpty());

        verify(nodes, never()).findFirstUserMessageOfSessions(anyList());
    }

    @Test
    void deleteRemovesNodesThenTheRowForTheOwnerOnly() {
        ChatBranchSession mine = row("mine", "alice");
        when(sessions.findByAgentIdAndSessionId(agentId, "mine")).thenReturn(Optional.of(mine));

        assertTrue(store.deleteSessionOwnedBy(agentId, "mine", "alice"));

        verify(nodes).deleteOfSession(agentId, "mine");
        verify(sessions).delete(mine);
    }

    @Test
    void deleteOfAnotherOwnersOrAMissingSessionTouchesNothing() {
        ChatBranchSession bobs = row("bobs", "bob");
        when(sessions.findByAgentIdAndSessionId(agentId, "bobs")).thenReturn(Optional.of(bobs));
        when(sessions.findByAgentIdAndSessionId(agentId, "gone")).thenReturn(Optional.empty());

        assertFalse(store.deleteSessionOwnedBy(agentId, "bobs", "alice"));
        assertFalse(store.deleteSessionOwnedBy(agentId, "gone", "alice"));
        assertFalse(store.deleteSessionOwnedBy(agentId, "bobs", null));

        verifyNoInteractions(nodes);
        verify(sessions, never()).delete(any(ChatBranchSession.class));
    }
}
