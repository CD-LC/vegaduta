package com.agenticai.core.agents.branching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.branching.ChatBranchDtos.ThreadView;
import com.agenticai.core.agents.branching.ChatBranchDtos.ThreadsResponse;
import com.agenticai.core.agents.branching.ChatBranchServiceTestBase.FakeStore;
import com.agenticai.core.agents.branching.ChatBranchTree.Node;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link ChatThreadService}: the caller's own sessions only, newest first, titled by the first user
 * message, paged on {@code updatedAt}; a delete of anyone else's session is a 404 that reveals
 * nothing.
 */
class ChatThreadServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z");

    private final FakeStore store = new FakeStore();
    private final SessionMemoryService sessionMemory = mock(SessionMemoryService.class);
    private final UUID agentId = UUID.randomUUID();
    private ChatThreadService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("t1", "alice", Set.of("tenant_user")));
        service = new ChatThreadService(store, sessionMemory, true);
    }

    /** SEC-004: the id chat stores {@code subject}'s turns on {@code sid} under. */
    private String scoped(String subject, String sid) {
        return SessionMemoryService.scopedSessionId("t1", agentId, subject, sid);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * A session of {@code owner} last touched at {@code at}, with the given turns (user, assistant,
     * user, ...).
     */
    private void session(String owner, String sessionId, Instant at, String... turns) {
        List<Node> nodes = new ArrayList<>();
        UUID parent = null;
        for (int i = 0; i < turns.length; i++) {
            Node n =
                    new Node(
                            UUID.randomUUID(),
                            parent,
                            i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT,
                            turns[i],
                            0,
                            T0.plusSeconds(i));
            nodes.add(n);
            parent = n.id();
        }
        store.save(new ChatBranchStore.State(agentId, sessionId, owner, parent, null), nodes);
        store.touch(agentId, sessionId, at);
    }

    private static List<String> ids(ThreadsResponse page) {
        List<String> out = new ArrayList<>();
        for (ThreadView t : page.threads()) {
            out.add(t.sessionId());
        }
        return out;
    }

    @Test
    void listsOnlyTheCallersSessionsNewestFirstWithTheFirstUserLineAsTitle() {
        session("alice", "old", T0, "book a dentist\nnext week", "sure");
        session("alice", "new", T0.plusSeconds(60), "plan a trip", "where to?", "lisbon");
        session("bob", "bobs", T0.plusSeconds(120), "bob's secret");

        ThreadsResponse page = service.list(null, null);

        assertEquals(List.of("new", "old"), ids(page));
        ThreadView newest = page.threads().get(0);
        assertEquals(agentId, newest.agentId());
        assertEquals("plan a trip", newest.title());
        assertEquals(T0.plusSeconds(60), newest.updatedAt());
        assertEquals(
                "book a dentist\nnext week",
                page.threads().get(1).title(),
                "the stored line, uncut below 200 chars");
        assertNull(page.nextBefore(), "no further page");
    }

    @Test
    void aSessionWithoutAUserMessageHasANullTitleAndALongOneIsCutTo200Chars() {
        session("alice", "empty", T0);
        session("alice", "long", T0.plusSeconds(1), "x".repeat(500));

        ThreadsResponse page = service.list(null, null);

        assertEquals(List.of("long", "empty"), ids(page));
        assertEquals(200, page.threads().get(0).title().length());
        assertNull(page.threads().get(1).title());
    }

    @Test
    void pagesOnUpdatedAtAndHandsBackTheCursorOnlyWhileThereIsMore() {
        for (int i = 0; i < 5; i++) {
            session("alice", "s" + i, T0.plusSeconds(i), "q" + i);
        }

        ThreadsResponse first = service.list(2, null);
        assertEquals(List.of("s4", "s3"), ids(first));
        assertEquals(T0.plusSeconds(3), first.nextBefore());

        ThreadsResponse second = service.list(2, first.nextBefore());
        assertEquals(List.of("s2", "s1"), ids(second));
        assertEquals(T0.plusSeconds(1), second.nextBefore());

        ThreadsResponse last = service.list(2, second.nextBefore());
        assertEquals(List.of("s0"), ids(last));
        assertNull(last.nextBefore());
    }

    @Test
    void limitIsBoundedAndDefaultsTo50() {
        assertEquals(50, ChatThreadService.clampLimit(null));
        assertEquals(200, ChatThreadService.clampLimit(200));
        assertThrows(IllegalArgumentException.class, () -> service.list(0, null));
        assertThrows(IllegalArgumentException.class, () -> service.list(201, null));
    }

    @Test
    void deleteRemovesTheCallersSessionWithItsNodesAndItsLiveHistory() {
        session("alice", "mine", T0, "hello", "hi");
        assertFalse(store.findNodes(agentId, "mine").isEmpty());

        service.delete(agentId, "mine");

        assertTrue(store.findState(agentId, "mine").isEmpty());
        assertTrue(store.findNodes(agentId, "mine").isEmpty());
        assertTrue(service.list(null, null).threads().isEmpty());
        // The Redis list the tree would be rebuilt from goes too - alice's own scoped list, nobody
        // else's.
        verify(sessionMemory).rewind(scoped("alice", "mine"), 0);
        verify(sessionMemory, never()).rewind(scoped("bob", "mine"), 0);
    }

    @Test
    void deleteOfSomeoneElsesOrAnUnknownSessionIs404AndChangesNothing() {
        session("bob", "bobs", T0, "bob's secret");

        assertNotFound(() -> service.delete(agentId, "bobs"));
        assertNotFound(() -> service.delete(agentId, "never-existed"));
        assertNotFound(() -> service.delete(agentId, " "));

        assertTrue(store.findState(agentId, "bobs").isPresent());
        assertEquals(1, store.findNodes(agentId, "bobs").size());
        verify(sessionMemory, never()).rewind(anyString(), anyInt());
    }

    @Test
    void flagOffAnswers404ForBothCalls() {
        session("alice", "mine", T0, "hello");
        ChatThreadService off = new ChatThreadService(store, sessionMemory, false);

        assertNotFound(() -> off.list(null, null));
        assertNotFound(() -> off.delete(agentId, "mine"));
        assertTrue(store.findState(agentId, "mine").isPresent());
        verify(sessionMemory, never()).rewind(anyString(), anyInt());
    }

    @Test
    void aCallerWithoutASubjectSeesNoThreadsAndCannotDelete() {
        session("alice", "mine", T0, "hello");
        TenantContext.set(TenantContext.of("t1", null, Set.of("tenant_user")));

        assertTrue(service.list(null, null).threads().isEmpty());
        assertNotFound(() -> service.delete(agentId, "mine"));
    }

    @Test
    void titleStripsTheFailureMarkerAndSurroundingWhitespace() {
        assertEquals("hello", ChatThreadService.titleOf("  hello \n"));
        assertNull(ChatThreadService.titleOf("   "));
        assertNull(ChatThreadService.titleOf(null));
    }

    @Test
    void theTitleIsThePersonsOwnWordsNotTheAppContextBlockTheConsolePutsAheadOfThem() {
        String stored =
                "<app_context>\nAdded by the app, not typed by the user.\nConnected: none.\n</app_context>\n\n"
                        + "Find flights to Lisbon next week";

        assertEquals("Find flights to Lisbon next week", ChatThreadService.titleOf(stored));
        // A thread stored before this fix is titled the same way: the title is derived when read.
        assertEquals("plain", ChatThreadService.titleOf("plain"));
        // Only app text (a block never closed, or nothing after it): no title rather than the tag.
        assertNull(ChatThreadService.titleOf("<app_context>\nConnected: none."));
        assertNull(ChatThreadService.titleOf("<app_context>x</app_context>\n  "));
        // The tag inside what the person typed is theirs.
        assertEquals(
                "explain <app_context> tags", ChatThreadService.titleOf("explain <app_context> tags"));
    }

    private static void assertNotFound(org.junit.jupiter.api.function.Executable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call);
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }
}
