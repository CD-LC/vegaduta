package com.agenticai.core.agents.branching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.branching.ChatBranchDtos.ActivePathResponse;
import com.agenticai.core.agents.branching.ChatBranchDtos.MessageView;
import com.agenticai.core.agents.branching.ChatBranchDtos.SiblingsResponse;
import com.agenticai.core.agents.branching.ChatBranchTree.Node;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.cache.SemanticResponseCacheService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link ChatBranchService} authorisation (RUNNER bar, per-owner trees, session-id validation),
 * node limits and sibling-slot concurrency. Split out of {@link ChatBranchServiceTest}.
 */
class ChatBranchServiceAccessTest extends ChatBranchServiceTestBase {

    // ---- authorisation --------------------------------------------------------

    @Test
    void aCallerWithoutRunnerAccessToTheAgentIsRefusedBeforeAnythingIsTouched() {
        doThrow(new ResourceAccessDeniedException())
                .when(agentService)
                .requireAccess(agentId, PermissionLevel.RUNNER);

        assertThrows(
                ResourceAccessDeniedException.class, () -> service.activePath(agentId, SESSION));
        assertThrows(ResourceAccessDeniedException.class, () -> service.capabilities(agentId));
        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.prepareEdit(agentId, SESSION, UUID.randomUUID(), "x"));
        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.prepareRegenerate(agentId, SESSION, UUID.randomUUID()));
        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.switchActiveLeaf(agentId, SESSION, UUID.randomUUID()));

        verifyNoInteractions(sessionMemory);
        assertTrue(store.states.isEmpty());
    }

    @Test
    void theRunnerBarIsTheSameOneNormalChatUses() {
        service.activePath(agentId, SESSION);
        verify(agentService).requireAccess(agentId, PermissionLevel.RUNNER);
    }

    @Test
    void anotherUserWithRunnerAccessCannotReadOrChangeSomeoneElsesTree() {
        chat("q1", "r1");
        ActivePathResponse mine = service.activePath(agentId, SESSION);
        // The first call that changes the active branch binds the tree to its caller.
        service.switchActiveLeaf(agentId, SESSION, mine.messages().get(0).id());

        TenantContext.set(TenantContext.of(TENANT, "mallory", Set.of("tenant_user")));

        assertNotFound(() -> service.activePath(agentId, SESSION));
        assertNotFound(() -> service.siblings(agentId, SESSION, mine.messages().get(0).id()));
        assertNotFound(
                () -> service.prepareEdit(agentId, SESSION, mine.messages().get(0).id(), "pwned"));
        assertNotFound(
                () -> service.switchActiveLeaf(agentId, SESSION, mine.messages().get(0).id()));
        assertEquals(List.of("q1", "r1"), liveTexts(live));
    }

    @Test
    void aStrangerWhoKnowsTheSessionIdSeesNoneOfItAndDoesNotLockTheRealUserOut() {
        chat("q1", "r1");
        TenantContext.set(TenantContext.of(TENANT, "mallory", Set.of("tenant_user")));
        // mallory's live list for SESSION is her own (empty): nothing of alice's comes back.
        assertTrue(service.activePath(agentId, SESSION).messages().isEmpty());
        assertNotFound(() -> service.prepareEdit(agentId, SESSION, UUID.randomUUID(), "x"));
        assertTrue(
                store.states.values().stream().allMatch(s -> s.ownerSubject() == null),
                "a read or a refused write by a stranger never binds the tree");

        TenantContext.set(TenantContext.of(TENANT, "alice", Set.of("tenant_user")));
        ActivePathResponse mine = service.activePath(agentId, SESSION);
        assertEquals(List.of("q1", "r1"), texts(mine));
        ChatBranchService.PreparedTurn prepared =
                service.prepareEdit(agentId, SESSION, mine.messages().get(0).id(), "q1 edited");

        assertEquals("q1 edited", prepared.text());
        TenantContext.set(TenantContext.of(TENANT, "mallory", Set.of("tenant_user")));
        assertNotFound(() -> service.activePath(agentId, SESSION));
    }

    @Test
    void recordingTheOwnersTurnsBindsTheTreeToThem() {
        chat("q1", "r1");
        service.activePath(agentId, SESSION);

        assertTrue(store.states.values().stream().allMatch(s -> "alice".equals(s.ownerSubject())));
        TenantContext.set(TenantContext.of(TENANT, "mallory", Set.of("tenant_user")));
        assertNotFound(() -> service.activePath(agentId, SESSION));
    }

    @Test
    void anotherRunnerCannotReadOrChangeAnUnownedTreeThatHoldsSomeoneElsesMessages() {
        // An unowned tree with nodes (not reachable through the service any more; kept closed).
        Node q1 = new Node(UUID.randomUUID(), null, MessageRole.USER, "q1", 0, Instant.now());
        Node r1 =
                new Node(UUID.randomUUID(), q1.id(), MessageRole.ASSISTANT, "r1", 0, Instant.now());
        store.save(
                new ChatBranchStore.State(agentId, SESSION, null, r1.id(), null), List.of(q1, r1));
        TenantContext.set(TenantContext.of(TENANT, "mallory", Set.of("tenant_user")));

        assertNotFound(() -> service.activePath(agentId, SESSION));
        assertNotFound(() -> service.siblings(agentId, SESSION, q1.id()));
        assertNotFound(() -> service.switchActiveLeaf(agentId, SESSION, q1.id()));
        assertNotFound(() -> service.prepareEdit(agentId, SESSION, q1.id(), "pwned"));
        assertNotFound(() -> service.prepareRegenerate(agentId, SESSION, r1.id()));
        verify(sessionMemory, never()).replaceHistory(anyString(), anyList());
        assertTrue(
                store.states.values().stream().allMatch(s -> s.ownerSubject() == null),
                "the refused calls never bind the tree to mallory");

        // alice, the live session's recorded owner, can still read and branch it.
        TenantContext.set(TenantContext.of(TENANT, "alice", Set.of("tenant_user")));
        chat("q1", "r1");
        assertEquals(List.of("q1", "r1"), texts(service.activePath(agentId, SESSION)));
        assertEquals(
                "q1 edited", service.prepareEdit(agentId, SESSION, q1.id(), "q1 edited").text());
    }

    @Test
    void removingAUsersTreesDeletesOnlyTheirs() {
        chat("q1", "r1");
        ActivePathResponse mine = service.activePath(agentId, SESSION);
        service.switchActiveLeaf(agentId, SESSION, mine.messages().get(0).id());
        assertEquals(2, store.nodes.size());

        assertEquals(0, store.deleteSessionsOwnedBy("someone-else"));
        assertEquals(2, store.nodes.size());
        assertEquals(1, store.deleteSessionsOwnedBy("alice"));
        assertTrue(store.nodes.isEmpty());
        assertTrue(store.states.isEmpty());
    }

    @Test
    void aSessionIdOfAnotherAgentFindsNoTree() {
        chat("q1", "r1");
        service.activePath(agentId, SESSION);

        UUID otherAgent = UUID.randomUUID();
        // The store is keyed by (agent, session), so the other agent starts from nothing; its live
        // transcript read is the same Redis key here only because the fake shares one list.
        live.clear();
        assertTrue(service.activePath(otherAgent, SESSION).messages().isEmpty());
    }

    @Test
    void anOverlongOrBlankSessionIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.activePath(agentId, " "));
        assertThrows(
                IllegalArgumentException.class, () -> service.activePath(agentId, "x".repeat(129)));
    }

    // ---- limits and concurrency -----------------------------------------------

    @Test
    void editAndRegenerateAnswer409OnceTheSessionIsAtItsNodeLimit() {
        ChatBranchService small = newService(true, 10);
        for (int i = 0; i < 5; i++) {
            chat("q" + i, "r" + i);
        }
        ActivePathResponse path = small.activePath(agentId, SESSION);
        assertEquals(10, path.messages().size());

        ResponseStatusException edit =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                small.prepareEdit(
                                        agentId, SESSION, path.messages().get(0).id(), "x"));
        assertEquals(HttpStatus.CONFLICT, edit.getStatusCode());
        assertThrows(
                ResponseStatusException.class,
                () -> small.prepareRegenerate(agentId, SESSION, path.messages().get(1).id()));
        assertEquals(10, liveTexts(live).size(), "a refused call leaves the live transcript alone");
    }

    @Test
    void newTurnsBeyondTheNodeLimitAreSimplyNotRecorded() {
        ChatBranchService small = newService(true, 10);
        for (int i = 0; i < 5; i++) {
            chat("q" + i, "r" + i);
        }
        small.activePath(agentId, SESSION);
        chat("one too many", "reply");

        ActivePathResponse path = small.activePath(agentId, SESSION);

        assertEquals(10, path.messages().size());
        assertEquals(10, store.nodes.size());
    }

    @Test
    void aSiblingSlotConflictIsRetriedOnceAgainstFreshState() {
        chat("q1", "r1");
        store.failNextSaves.set(1);

        ActivePathResponse path = service.activePath(agentId, SESSION);

        assertEquals(List.of("q1", "r1"), texts(path));
        assertEquals(2, store.nodes.size());
    }

    @Test
    void aSecondConsecutiveConflictIsNotSwallowed() {
        chat("q1", "r1");
        store.failNextSaves.set(2);
        assertThrows(
                DataIntegrityViolationException.class, () -> service.activePath(agentId, SESSION));
    }

    @Test
    void siblingsKeepTheirCreationOrderEvenWhenReadBackInAnyStorageOrder() {
        chat("q1", "r1");
        UUID q1 = service.activePath(agentId, SESSION).messages().get(0).id();
        service.prepareRegenerate(agentId, SESSION, q1);
        chat("q1", "r1 b");
        service.activePath(agentId, SESSION);
        service.prepareRegenerate(agentId, SESSION, q1);
        chat("q1", "r1 c");
        ActivePathResponse path = service.activePath(agentId, SESSION);

        SiblingsResponse siblings = service.siblings(agentId, SESSION, path.messages().get(1).id());

        assertEquals(3, siblings.count());
        assertEquals(2, siblings.index());
        assertNotEquals(siblings.ids().get(0), siblings.ids().get(2));
        assertEquals("r1", store.nodes.get(siblings.ids().get(0)).content());
        assertEquals("r1 c", store.nodes.get(siblings.ids().get(2)).content());
    }
}
