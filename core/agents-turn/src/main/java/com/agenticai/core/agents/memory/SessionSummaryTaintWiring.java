package com.agenticai.core.agents.memory;

import com.agenticai.core.guardrails.taint.ConversationTaintStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hands {@link SessionMemoryService} the conversation-taint store and the replay mode for an untrusted rolling
 * summary. A separate bean, like {@code ConversationTaintWiring}, because {@code SessionMemoryService} is over
 * the file-size ratchet and may not grow, and its constructor is already wide.
 *
 * <p>{@code app.chat.history-summary-untrusted-replay}: {@code wrap} (default) replays a summary folded from a
 * tainted conversation inside Spotlight {@code UNTRUSTED_DATA} delimiters; {@code skip} leaves it out. With no
 * taint store (taint mode off) only the previous flag and the memory write screen mark a summary untrusted.
 */
@Component
class SessionSummaryTaintWiring {

    @Autowired
    SessionSummaryTaintWiring(
            SessionMemoryService sessions,
            ObjectProvider<ConversationTaintStore> store,
            @Value("${app.chat.history-summary-untrusted-replay:wrap}") String replayMode) {
        sessions.configureSummaryTaint(
                store.getIfAvailable(), "skip".equalsIgnoreCase(replayMode == null ? "" : replayMode.strip()));
    }
}
