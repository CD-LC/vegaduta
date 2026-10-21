package com.agenticai.core.agents.branching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.branching.ChatBranchDtos.ActivePathResponse;
import com.agenticai.core.agents.branching.ChatBranchDtos.MessageView;
import com.agenticai.core.agents.branching.ChatBranchTree.Node;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.cache.SemanticResponseCacheService;
import com.agenticai.core.security.TenantContext;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared fixture for the {@link ChatBranchService} tests: an in-memory {@link ChatBranchStore} and
 * a Redis stand-in for alice's live transcript. See {@link ChatBranchServiceTest}.
 */
abstract class ChatBranchServiceTestBase {

    static final String SESSION = "sess-1";
    static final String TENANT = "t1";

    final UUID agentId = UUID.randomUUID();
    final AgentService agentService = mock(AgentService.class);
    final SessionMemoryService sessionMemory = mock(SessionMemoryService.class);
    final LlmResponseCacheService responseCache = mock(LlmResponseCacheService.class);
    final SemanticResponseCacheService semanticCache = mock(SemanticResponseCacheService.class);

    @SuppressWarnings("unchecked")
    final ObjectProvider<SemanticResponseCacheService> semanticProvider =
            mock(ObjectProvider.class);

    final FakeStore store = new FakeStore();

    /** What Redis holds for SESSION. */
    final List<ChatTurn> live = new ArrayList<>();

    int clock = 0;

    ChatBranchService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of(TENANT, "alice", Set.of("tenant_user")));
        // SEC-004: alice's chat turns live under HER scoped id, never under SESSION itself; her
        // first chat append recorded her as that list's owner. Anyone else's scoped id for SESSION
        // reads the mock's default: an empty list nobody owns.
        when(sessionMemory.getFullHistory(scoped("alice")))
                .thenAnswer(inv -> new ArrayList<>(live));
        when(sessionMemory.isOwnedBy(scoped("alice"), TENANT, "alice")).thenReturn(true);
        doAnswer(
                        inv -> {
                            live.clear();
                            List<ChatTurn> turns = inv.getArgument(1);
                            live.addAll(turns);
                            return null;
                        })
                .when(sessionMemory)
                .replaceHistory(eq(scoped("alice")), anyList());
        when(semanticProvider.getIfAvailable()).thenReturn(semanticCache);
        service = newService(true, 1000);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** What chat writes {@code subject}'s turns on SESSION under (AgentChatService#chat). */
    String scoped(String subject) {
        return SessionMemoryService.scopedSessionId(TENANT, agentId, subject, SESSION);
    }

    ChatBranchService newService(boolean enabled, int maxNodes) {
        return new ChatBranchService(
                agentService,
                sessionMemory,
                store,
                responseCache,
                semanticProvider,
                enabled,
                maxNodes);
    }

    void chat(String user, String assistant) {
        live.add(
                new ChatTurn(
                        MessageRole.USER,
                        user,
                        Instant.parse("2026-10-01T10:00:00Z").plusSeconds(clock++)));
        if (assistant != null) {
            live.add(
                    new ChatTurn(
                            MessageRole.ASSISTANT,
                            assistant,
                            Instant.parse("2026-10-01T10:00:00Z").plusSeconds(clock++)));
        }
    }

    static List<String> texts(ActivePathResponse path) {
        List<String> out = new ArrayList<>();
        for (MessageView m : path.messages()) {
            out.add(m.content());
        }
        return out;
    }

    static List<String> liveTexts(List<ChatTurn> turns) {
        List<String> out = new ArrayList<>();
        for (ChatTurn t : turns) {
            out.add(t.content());
        }
        return out;
    }

    static void assertNotFound(org.junit.jupiter.api.function.Executable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call);
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    /** In-memory {@link ChatBranchStore}, keyed like the real tables. */
    static final class FakeStore implements ChatBranchStore {
        final Map<String, State> states = new HashMap<>();
        final Map<UUID, Node> nodes = new HashMap<>();
        final Map<UUID, String> nodeKey = new HashMap<>();
        final AtomicInteger failNextSaves = new AtomicInteger();

        private static String key(UUID agentId, String sessionId) {
            return agentId + "|" + sessionId;
        }

        @Override
        public Optional<State> findState(UUID agentId, String sessionId) {
            return Optional.ofNullable(states.get(key(agentId, sessionId)));
        }

        @Override
        public List<Node> findNodes(UUID agentId, String sessionId) {
            String k = key(agentId, sessionId);
            List<Node> out = new ArrayList<>();
            for (Map.Entry<UUID, Node> e : nodes.entrySet()) {
                if (k.equals(nodeKey.get(e.getKey()))) {
                    out.add(e.getValue());
                }
            }
            // Deliberately not creation order: the service must not depend on storage order.
            out.sort((a, b) -> a.id().compareTo(b.id()));
            return out;
        }

        @Override
        public void save(State state, List<Node> newNodes) {
            if (failNextSaves.get() > 0) {
                failNextSaves.decrementAndGet();
                throw new DataIntegrityViolationException("uq_chat_branch_node_sibling");
            }
            String k = key(state.agentId(), state.sessionId());
            for (Node n : newNodes) {
                nodes.put(n.id(), n);
                nodeKey.put(n.id(), k);
            }
            states.put(k, state);
        }

        @Override
        public int deleteSessionsIdleBefore(Instant cutoff) {
            // The fake does not track update times; the retention sweep is tested against a mock
            // store.
            return 0;
        }

        @Override
        public int deleteSessionsOwnedBy(String subject) {
            List<String> doomed = new ArrayList<>();
            for (Map.Entry<String, State> e : states.entrySet()) {
                if (subject.equals(e.getValue().ownerSubject())) {
                    doomed.add(e.getKey());
                }
            }
            for (String key : doomed) {
                states.remove(key);
                nodeKey.values().removeIf(key::equals);
                nodes.keySet().removeIf(id -> !nodeKey.containsKey(id));
            }
            return doomed.size();
        }

        /** Per-session activity stamps, set by the thread-list tests ({@link #touch}). */
        final Map<String, Instant> updatedAt = new HashMap<>();

        void touch(UUID agentId, String sessionId, Instant at) {
            updatedAt.put(key(agentId, sessionId), at);
        }

        @Override
        public List<Thread> findThreadsOwnedBy(String subject, Instant before, int limit) {
            List<Thread> out = new ArrayList<>();
            for (Map.Entry<String, State> e : states.entrySet()) {
                State s = e.getValue();
                Instant at = updatedAt.getOrDefault(e.getKey(), Instant.EPOCH);
                if (!subject.equals(s.ownerSubject()) || (before != null && !at.isBefore(before))) {
                    continue;
                }
                Node first = null;
                for (Node n : findNodes(s.agentId(), s.sessionId())) {
                    if (n.role() == MessageRole.USER
                            && (first == null || n.createdAt().isBefore(first.createdAt()))) {
                        first = n;
                    }
                }
                out.add(
                        new Thread(
                                s.agentId(),
                                s.sessionId(),
                                first == null ? null : first.content(),
                                at,
                                at));
            }
            out.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        }

        @Override
        public boolean deleteSessionOwnedBy(UUID agentId, String sessionId, String subject) {
            String key = key(agentId, sessionId);
            State s = states.get(key);
            if (s == null || !subject.equals(s.ownerSubject())) {
                return false;
            }
            states.remove(key);
            nodeKey.values().removeIf(key::equals);
            nodes.keySet().removeIf(id -> !nodeKey.containsKey(id));
            return true;
        }
    }
}
