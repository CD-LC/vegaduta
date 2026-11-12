package com.agenticai.core.agents.memory;

import com.agenticai.core.guardrails.taint.ConversationTaintStore;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The {@link ConversationTaintStore} the conversation-level taint and the untrusted rolling summary
 * read, backed by the one persisted marker a conversation has: {@link SessionTaintStore}. There is
 * deliberately no second flag, so the audited clear ({@code DELETE
 * /api/agents/{agentId}/chat/sessions/{sessionId}/taint}) is the whole truth for every reader.
 *
 * <p>The conversation id is the SEC-004 scoped session id, which already folds in the tenant, so
 * the tenant id is not part of the key. With {@code app.agents.taint.session-persistence=false} a
 * conversation reads as not tainted. A marker that cannot be read is NOT reported as clean: the
 * read throws, and both readers then treat the conversation as tainted (fail closed).
 */
@Component
class SessionBackedConversationTaintStore implements ConversationTaintStore {

    private final SessionTaintStore sessions;

    SessionBackedConversationTaintStore(SessionTaintStore sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean isTainted(String tenantId, String conversationId) {
        return sessions.hasMarkerStrict(conversationId);
    }

    /**
     * Makes sure a marker exists. The causes are recorded by {@link SessionTaintStore#seedAndTrack}
     * as the turn names them; this call adds none of its own.
     */
    @Override
    public void markTainted(String tenantId, String conversationId) {
        sessions.record(conversationId, List.of());
    }
}
