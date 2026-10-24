package com.agenticai.core.guardrails;

/**
 * Where the RIGOROUS tool-call verifier gets the end of the conversation a tool call belongs to, so a
 * follow-up such as "yes, send it" is judged against what it answers and not against itself.
 *
 * <p>An optional collaborator: with no implementation wired, or no conversation known for the turn, the
 * verifier judges the current user message alone, as it did before. Implementations must be cheap and must
 * not throw for a missing conversation (return an empty string); the caller treats any failure as "no
 * context".
 */
@FunctionalInterface
public interface ConversationTailSource {

    /**
     * The last {@code maxMessages} messages of the conversation, oldest first, one per line as
     * {@code User: ...} / {@code Assistant: ...}, cut to at most {@code maxChars} characters by dropping the
     * OLDEST text first. Empty when there is nothing to show.
     *
     * @param memorySessionId the turn's scoped (tenant, agent, principal) memory session id, never the raw
     *     client-chosen id
     */
    String tail(String memorySessionId, int maxMessages, int maxChars);
}
