package com.agenticai.core.guardrails.taint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ties a turn's {@link TurnTaint} to the conversation it belongs to: a conversation that has had a
 * tainted turn starts every later turn tainted, and a turn that becomes tainted flags its
 * conversation, immediately and whichever thread marked it.
 *
 * <p>Tenant and conversation id are captured as plain strings when the turn is attached, so the
 * write that happens when a tool thread taints the turn needs no thread-local tenant context.
 * Fails closed: a store that cannot be read taints the turn; a store that cannot be written is
 * logged (the current turn is tainted regardless).
 */
public final class ConversationTaint {

    private static final Logger log = LoggerFactory.getLogger(ConversationTaint.class);

    /** Source label recorded on a turn tainted by its conversation. */
    public static final String SOURCE = "conversation:tainted";

    /** Source label recorded when the flag could not be read. */
    public static final String SOURCE_UNKNOWN = "conversation:taint_unknown";

    private final ConversationTaintStore store;

    public ConversationTaint(ConversationTaintStore store) {
        this.store = store;
    }

    /** Applies the persisted flag to {@code taint} and arranges for a new taint to be persisted. */
    public void attach(TurnTaint taint, String tenantId, String conversationId) {
        if (taint == null
                || store == null
                || tenantId == null
                || tenantId.isBlank()
                || conversationId == null
                || conversationId.isBlank()) {
            return;
        }
        try {
            if (store.isTainted(tenantId, conversationId)) {
                taint.mark(SOURCE);
                return;
            }
        } catch (RuntimeException e) {
            log.warn("Conversation taint flag unreadable; treating the turn as tainted", e);
            taint.mark(SOURCE_UNKNOWN);
            return;
        }
        taint.onTainted(
                () -> {
                    try {
                        store.markTainted(tenantId, conversationId);
                    } catch (RuntimeException e) {
                        log.warn("Could not persist the conversation taint flag", e);
                    }
                });
    }
}
