package com.agenticai.core.agents.branching;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ChatBranchStore} over the V450 tables. The tenant schema comes from the ambient
 * connection, as for every JPA repository.
 */
@Component
public class JpaChatBranchStore implements ChatBranchStore {

    private final ChatBranchNodeRepository nodes;
    private final ChatBranchSessionRepository sessions;

    public JpaChatBranchStore(
            ChatBranchNodeRepository nodes, ChatBranchSessionRepository sessions) {
        this.nodes = nodes;
        this.sessions = sessions;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<State> findState(UUID agentId, String sessionId) {
        return sessions.findByAgentIdAndSessionId(agentId, sessionId)
                .map(ChatBranchSession::toState);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatBranchTree.Node> findNodes(UUID agentId, String sessionId) {
        List<ChatBranchTree.Node> out = new ArrayList<>();
        for (ChatBranchNode n :
                nodes.findByAgentIdAndSessionIdOrderByCreatedAtAsc(agentId, sessionId)) {
            out.add(n.toNode());
        }
        return out;
    }

    @Override
    @Transactional
    public void save(State state, List<ChatBranchTree.Node> newNodes) {
        List<ChatBranchNode> rows = new ArrayList<>(newNodes.size());
        for (ChatBranchTree.Node node : newNodes) {
            rows.add(new ChatBranchNode(state.agentId(), state.sessionId(), node));
        }
        // Nodes first: if the sibling-slot unique index rejects one, the state row is not touched
        // either.
        nodes.saveAllAndFlush(rows);
        ChatBranchSession row =
                sessions.findByAgentIdAndSessionId(state.agentId(), state.sessionId())
                        .orElseGet(() -> new ChatBranchSession(state.agentId(), state.sessionId()));
        row.apply(state);
        sessions.saveAndFlush(row);
    }

    @Override
    @Transactional
    public int deleteSessionsIdleBefore(Instant cutoff) {
        // Nodes first: they are located through the session rows.
        nodes.deleteOfSessionsIdleBefore(cutoff);
        return sessions.deleteIdleBefore(cutoff);
    }

    @Override
    @Transactional
    public int deleteSessionsOwnedBy(String subject) {
        if (subject == null || subject.isBlank()) {
            return 0;
        }
        nodes.deleteOfSessionsOwnedBy(subject);
        return sessions.deleteOwnedBy(subject);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Thread> findThreadsOwnedBy(String subject, Instant before, int limit) {
        if (subject == null || subject.isBlank() || limit <= 0) {
            return List.of();
        }
        PageRequest page = PageRequest.of(0, limit);
        List<ChatBranchSession> rows =
                before == null
                        ? sessions.findByOwnerSubjectOrderByUpdatedAtDesc(subject, page)
                        : sessions.findByOwnerSubjectAndUpdatedAtBeforeOrderByUpdatedAtDesc(
                                subject, before, page);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(rows.size());
        for (ChatBranchSession row : rows) {
            ids.add(row.getId());
        }
        // One query for the whole page (no N+1): the earliest USER node per session.
        Map<String, String> firstUserMessage = new HashMap<>();
        for (Object[] r : nodes.findFirstUserMessageOfSessions(ids)) {
            firstUserMessage.put(threadKey(toUuid(r[0]), String.valueOf(r[1])), (String) r[2]);
        }
        List<Thread> out = new ArrayList<>(rows.size());
        for (ChatBranchSession row : rows) {
            out.add(
                    new Thread(
                            row.getAgentId(),
                            row.getSessionId(),
                            firstUserMessage.get(threadKey(row.getAgentId(), row.getSessionId())),
                            row.getCreatedAt(),
                            row.getUpdatedAt()));
        }
        return out;
    }

    @Override
    @Transactional
    public boolean deleteSessionOwnedBy(UUID agentId, String sessionId, String subject) {
        if (subject == null || subject.isBlank()) {
            return false;
        }
        ChatBranchSession row = sessions.findByAgentIdAndSessionId(agentId, sessionId).orElse(null);
        if (row == null || !subject.equals(row.getOwnerSubject())) {
            return false;
        }
        // Nodes first: they are located through the session row.
        nodes.deleteOfSession(agentId, sessionId);
        sessions.delete(row);
        return true;
    }

    private static String threadKey(UUID agentId, String sessionId) {
        return agentId + "|" + sessionId;
    }

    /**
     * The native query hands the uuid column back as a UUID on Postgres; H2 and some drivers give a
     * String.
     */
    private static UUID toUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }
}
