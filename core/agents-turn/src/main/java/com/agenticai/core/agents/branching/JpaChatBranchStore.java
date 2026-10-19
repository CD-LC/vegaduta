package com.agenticai.core.agents.branching;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link ChatBranchStore} over the V450 tables. The tenant schema comes from the ambient connection, as for every JPA repository. */
@Component
public class JpaChatBranchStore implements ChatBranchStore {

    private final ChatBranchNodeRepository nodes;
    private final ChatBranchSessionRepository sessions;

    public JpaChatBranchStore(ChatBranchNodeRepository nodes, ChatBranchSessionRepository sessions) {
        this.nodes = nodes;
        this.sessions = sessions;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<State> findState(UUID agentId, String sessionId) {
        return sessions.findByAgentIdAndSessionId(agentId, sessionId).map(ChatBranchSession::toState);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatBranchTree.Node> findNodes(UUID agentId, String sessionId) {
        List<ChatBranchTree.Node> out = new ArrayList<>();
        for (ChatBranchNode n : nodes.findByAgentIdAndSessionIdOrderByCreatedAtAsc(agentId, sessionId)) {
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
        // Nodes first: if the sibling-slot unique index rejects one, the state row is not touched either.
        nodes.saveAllAndFlush(rows);
        ChatBranchSession row = sessions.findByAgentIdAndSessionId(state.agentId(), state.sessionId())
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
}
