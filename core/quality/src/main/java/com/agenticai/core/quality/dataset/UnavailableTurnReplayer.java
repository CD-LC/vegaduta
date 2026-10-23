package com.agenticai.core.quality.dataset;

import java.time.Duration;
import java.util.UUID;

/**
 * The replayer a context falls back to when no {@link AgentTurnReplayer} bean exists - a module-scoped slice
 * that has {@code core/quality} but not the chat service. It lets the services construct (a boot failure for
 * a missing optional collaborator would be the wrong blast radius) and fails every replay with a message that
 * says what is missing, so a case ends as an ERROR result, never as a made-up reply.
 */
public final class UnavailableTurnReplayer implements AgentTurnReplayer {

    private static IllegalStateException unavailable() {
        return new IllegalStateException(
            "Eval replay is unavailable: no AgentTurnReplayer is registered. The application module provides it"
                + " (AgentChatServiceTurnReplayer); a context without the chat service cannot replay a turn.");
    }

    @Override
    public ReplayedTurn replay(
        UUID agentId, String sessionId, String subjectKey, String userMessage,
        boolean executeTools, Duration timeout
    ) {
        throw unavailable();
    }

    @Override
    public ReplayedTurn replay(ReplayRequest request) {
        throw unavailable();
    }
}
