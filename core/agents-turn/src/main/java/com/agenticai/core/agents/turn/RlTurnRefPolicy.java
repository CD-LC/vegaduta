package com.agenticai.core.agents.turn;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides which console chat turns carry a turn reference, which is what lets the reinforcement-learning bandits
 * (model route, tool subset, prompt variant, generation parameters, reasoning depth) vary the turn and record an
 * episode for it.
 *
 * <p>As shipped ({@code rl.selection.server-turn-ref=false}) only a turn whose CLIENT sent a {@code turnRef} has one:
 * the Playground, so exploration stays on test traffic. With {@code true} the console chat endpoint
 * ({@code POST /api/agents/{id}/chat}) also gives a turn that came without one a reference of its own
 * ({@value #SERVER_PREFIX}&lt;random&gt;), so the bandits reach real conversations. It is applied at that one endpoint on
 * purpose: agent jobs, scheduled runs, evals, the lab, channel adapters and the continuation turn after an approval
 * build their own requests and never come through it, so none of them explores. No client knows a server-made
 * reference, so a thumbs-up cannot reward it - only the signals the server itself records can.
 */
@Component
public class RlTurnRefPolicy {

    /** Prefix of a turn reference made by the server rather than sent by a client. */
    public static final String SERVER_PREFIX = "srv:";

    private final boolean serverTurnRef;

    public RlTurnRefPolicy(@Value("${rl.selection.server-turn-ref:false}") boolean serverTurnRef) {
        this.serverTurnRef = serverTurnRef;
    }

    /** The turn reference to use for a console chat turn: the client's when it sent one, else per the setting. */
    public String turnRefFor(String clientTurnRef) {
        if (clientTurnRef != null && !clientTurnRef.isBlank()) {
            return clientTurnRef;
        }
        return serverTurnRef ? SERVER_PREFIX + UUID.randomUUID() : clientTurnRef;
    }
}
