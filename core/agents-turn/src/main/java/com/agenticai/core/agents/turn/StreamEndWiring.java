package com.agenticai.core.agents.turn;

import com.agenticai.core.chat.StreamEndExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Hands the {@link StreamEndExecutor} to the plain-object collaborators {@link AgentChatService}
 * builds for the end of a stream (output-guardrail audit, {@code agent.message.completed}, RL
 * episode close).
 *
 * <p>A separate bean instead of another setter on {@code AgentChatService} because that file is at
 * its size ceiling and its collaborators are not beans. Runs after every singleton exists. With
 * {@code app.chat.stream-end.async=false} (the default) the executor runs everything inline, so
 * wiring it is behaviour-neutral.
 */
@Component
class StreamEndWiring implements SmartInitializingSingleton {

    private final ObjectProvider<AgentChatService> chat;
    private final ObjectProvider<StreamEndExecutor> executor;

    StreamEndWiring(
            ObjectProvider<AgentChatService> chat, ObjectProvider<StreamEndExecutor> executor) {
        this.chat = chat;
        this.executor = executor;
    }

    @Override
    public void afterSingletonsInstantiated() {
        AgentChatService svc = chat.getIfAvailable();
        StreamEndExecutor streamEnd = executor.getIfAvailable();
        if (svc == null || streamEnd == null) {
            return;
        }
        svc.guardrails.setStreamEnd(streamEnd);
        svc.events.setStreamEnd(streamEnd);
        svc.rlEpisodes.setStreamEnd(streamEnd);
    }
}
