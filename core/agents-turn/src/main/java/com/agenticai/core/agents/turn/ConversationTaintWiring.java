package com.agenticai.core.agents.turn;

import com.agenticai.core.guardrails.taint.ConversationTaint;
import com.agenticai.core.guardrails.taint.ConversationTaintStore;
import com.agenticai.core.guardrails.taint.TaintMode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hands the conversation-level taint ({@link ConversationTaint}) to the chat turn's preflight. A
 * separate bean, rather than a setter on {@link AgentChatService}, because that class is over the
 * file-size ratchet and may not grow. Inert (nothing handed over, no Redis access per turn) when
 * {@code app.agents.taint.mode} is off or no store is available.
 */
@Component
class ConversationTaintWiring {

    ConversationTaintWiring(
            AgentChatService chat,
            ObjectProvider<ConversationTaintStore> store,
            @Value("${app.agents.taint.mode:observe}") String mode) {
        ConversationTaintStore s = store.getIfAvailable();
        if (s != null && TaintMode.parse(mode) != TaintMode.OFF) {
            chat.preflight.conversationTaint = new ConversationTaint(s);
        }
    }
}
