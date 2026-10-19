package com.agenticai.core.agents.consent;

import java.util.UUID;
import reactor.core.publisher.Flux;

/**
 * Optional per-agent "notice and consent before processing" step for channel conversations.
 * Implemented in {@code core/app} (it needs the consent ledger); declared here because both {@code
 * AgentChatService} (this module) and {@code ChannelSessionService} ({@code core/omnichannel})
 * must consult it and neither may depend on the app module.
 *
 * <p>Both methods are no-ops (return {@code null} / {@code true}) for any turn that is not an
 * inbound channel conversation of an agent that has the consent step switched on, so the console
 * Playground, workflows, voice and API callers are unaffected.
 */
public interface ConversationConsentGate {

    /**
     * Called first in the chat turn, before moderation, caches, memory recall, tool resolution or
     * any model call. A non-null result REPLACES the turn: the caller sends exactly that text and
     * nothing else runs, so no model sees the message, no tool executes and no memory is written.
     * Handles the contact's answer to the notice itself (agree / decline / withdraw / language).
     *
     * <p>Not fail-open: an exception propagates and the turn fails, because answering a contact
     * whose consent state is unknown would defeat the setting.
     *
     * @return the replacement reply, or {@code null} to let the turn proceed
     */
    Flux<String> intercept(UUID agentId, String sessionId, String subjectKey, String userMessage);

    /**
     * Whether the inbound message text from this sender may be persisted (transcript row, message
     * preview). False while the agent requires consent and this sender has not granted it for the
     * current notice version.
     */
    boolean mayStoreContent(UUID agentId, String channel, String externalId);
}
