package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.consent.ConversationConsentGate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Hands the optional {@link ConversationConsentGate} (implemented in core/app) to the chat turn's
 * preflight. A separate bean, rather than a setter on {@link AgentChatService}, because that class
 * is over the file-size ratchet and may not grow.
 */
@Component
class ConsentGateWiring {

    ConsentGateWiring(AgentChatService chat, ObjectProvider<ConversationConsentGate> gate) {
        chat.preflight.consentGate = gate.getIfAvailable();
    }
}
