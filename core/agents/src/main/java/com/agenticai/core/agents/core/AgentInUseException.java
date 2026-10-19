package com.agenticai.core.agents.core;

/**
 * Thrown by {@link AgentService#delete} when a hard delete would violate a
 * DB foreign key (the agent is still bound as a channel session's agent or a
 * channel's default agent - see {@code channel_session}/{@code
 * channel_default_agent} migrations, neither of which cascades on agent
 * delete on purpose: an agent vanishing out from under a live channel binding
 * is worse than a clear "disconnect it first" error). Mapped to HTTP 409 in
 * {@code GlobalExceptionHandler}, same "translate a raw DB constraint failure
 * into an actionable message" reasoning as the DB-unavailable 503 handler.
 */
public class AgentInUseException extends RuntimeException {
    public AgentInUseException(String message) {
        super(message);
    }
}
