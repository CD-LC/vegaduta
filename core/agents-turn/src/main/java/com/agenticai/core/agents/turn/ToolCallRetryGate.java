package com.agenticai.core.agents.turn;

/** Whether a turn may re-send an unusable tool call (split from {@link FastStreamTurn}). */
final class ToolCallRetryGate {

    private ToolCallRetryGate() {}

    /**
     * A tool call the provider could not use is retried only when no tool has run this turn: the
     * retry re-sends the conversation without the tool results of this turn (Spring AI keeps them
     * inside its loop), so after a tool ran it could repeat the call's side effect.
     */
    static boolean mayRetry(
            TurnContext context,
            ChatTurnExecutor owner,
            java.util.UUID agentId,
            String sessionId,
            java.time.Instant since) {
        if (context.approval() != null && context.approval().isParked()) {
            return false;
        }
        try {
            return context.scope()
                            .get(() -> owner.collectToolEvidenceSince(agentId, sessionId, since))
                    == null;
        } catch (RuntimeException e) {
            ChatTurnExecutor.log.warn(
                    "Agent {} session {}: could not tell whether a tool ran this turn; not retrying the tool call",
                    agentId,
                    sessionId,
                    e);
            return false;
        }
    }
}
