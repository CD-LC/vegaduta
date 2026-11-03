package com.agenticai.core.guardrails.taint;

/**
 * Persisted conversation-level taint flag. A poisoned tool result or an untrusted sender's message
 * stays in the conversation history, so a later turn of the same conversation still has it in the
 * model's context even though that turn's own {@link TurnTaint} starts clean.
 *
 * <p>The flag only ever grows: there is deliberately no clear operation. A conversation is
 * identified by tenant plus the caller-scoped conversation id; the same id under another tenant is
 * a different conversation.
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
