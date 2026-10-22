package com.agenticai.core.chat;

import com.agenticai.core.providers.ToolRouterMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Wires {@link ConstrainedToolRouterChatModel} for the hosted-pool rows that opted in (see {@code
 * PlatformHostedLlmProvider#effectiveToolRouterMode}). Same shape as {@link ToolCallRecovery}: one
 * bean, one {@link #wrap} call from {@link LlmModelSupport#chatClientBuilder(ChatModel,
 * ToolRouterMode)}, nothing for a row that did not opt in.
 */
@Component
public class ConstrainedToolRouting {

    private final ResilientToolCallingManagerFactory toolCallingManagers;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final int maxRounds;

    public ConstrainedToolRouting(
            ResilientToolCallingManagerFactory toolCallingManagers,
            ObjectMapper objectMapper,
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${app.chat.tools.constrained-router.enabled:true}") boolean enabled,
            @Value(
                            "${app.chat.tools.constrained-router.max-rounds:"
                                    + ConstrainedToolRouterChatModel.DEFAULT_MAX_ROUNDS
                                    + "}")
                    int maxRounds) {
        this.toolCallingManagers = toolCallingManagers;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry == null ? null : meterRegistry.getIfAvailable();
        this.enabled = enabled;
        this.maxRounds = maxRounds;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * {@code model} routed in {@code mode}, or {@code model} itself when {@code mode} is null or
     * the feature is off.
     */
    public ChatModel wrap(ChatModel model, ToolRouterMode mode) {
        if (!enabled
                || mode == null
                || model == null
                || model instanceof ConstrainedToolRouterChatModel) {
            return model;
        }
        return new ConstrainedToolRouterChatModel(
                model, mode, toolCallingManagers.create(), objectMapper, maxRounds, meterRegistry);
    }
}
