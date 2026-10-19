package com.agenticai.core.guardrails;

import java.util.UUID;

/**
 * A single chat turn made more tool calls than {@code
 * app.guardrails.max-tool-calls-per-turn} allows - the direct fix for "the
 * customer will not face any infinite agent loop" (Autonomy/Guardrails
 * Strategy Stage A). Thrown from inside a {@link GuardedToolCallback}, which
 * means it surfaces to {@code AgentChatService} the same way any other
 * mid-stream tool failure does - see that class's own comment on the
 * inherent HTTP-streaming limit of mid-stream errors (headers may already
 * be committed by the time this fires).
 */
public class ToolStepLimitExceededException extends RuntimeException {

    public ToolStepLimitExceededException(UUID agentId, String sessionId, int maxSteps) {
        super(
            "Agent " + agentId + " (session " + sessionId + ") exceeded the maximum of "
                + maxSteps + " tool calls for a single turn - stopped to prevent a runaway loop."
        );
    }
}
