package com.agenticai.core.guardrails.taint;

/**
 * Persisted conversation-level taint flag. A poisoned tool result or an untrusted sender's message
 * stays in the conversation history, so a later turn of the same conversation still has it in the
 * model's context even though that turn's own {@link TurnTaint} starts clean.
 *
 * <p>Nothing in the turn pipeline clears the flag: it only grows as turns run. The one way back is
 * the audited, human decision behind {@code DELETE
 * /api/agents/{agentId}/chat/sessions/{sessionId}/taint}, which removes the session marker this
 * store reads. A conversation is identified by tenant plus the caller-scoped conversation id; the
 * same id under another tenant is a different conversation. An implementation that cannot read the
 * flag throws rather than answer "clean".
 */
public interface ConversationTaintStore {

    /**
     * True when the conversation has had a tainted turn. Implementations also keep the flag alive
     * for as long as the conversation is in use.
     */
    boolean isTainted(String tenantId, String conversationId);

    /** Records that the conversation has had a tainted turn. */
    void markTainted(String tenantId, String conversationId);
}
