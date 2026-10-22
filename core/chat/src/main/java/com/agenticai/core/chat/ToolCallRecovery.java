package com.agenticai.core.chat;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Settings and construction for {@link TextToolCallRecoveringChatModel}, the one place the
 * text-form tool-call recovery is switched on or off. {@link LlmModelSupport#chatClientBuilder}
 * wraps every provider model through {@link #wrap}; with {@code
 * app.chat.tools.text-call-recovery.enabled=false} the model is returned untouched and the request
 * path is byte-for-byte what it was before this existed.
 */
@Component
public class ToolCallRecovery {

    private final ResilientToolCallingManagerFactory toolCallingManagers;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final int maxPerInvocation;
    private final int holdBackChars;

    public ToolCallRecovery(
            ResilientToolCallingManagerFactory toolCallingManagers,
            ObjectMapper objectMapper,
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${app.chat.tools.text-call-recovery.enabled:true}") boolean enabled,
            @Value(
                            "${app.chat.tools.text-call-recovery.max-per-invocation:"
                                    + TextToolCallRecoveringChatModel.DEFAULT_MAX_RECOVERIES
                                    + "}")
                    int maxPerInvocation,
            @Value(
                            "${app.chat.tools.text-call-recovery.hold-back-chars:"
                                    + TextToolCallRecoveringChatModel.DEFAULT_HOLD_BACK_CHARS
                                    + "}")
                    int holdBackChars) {
        this.toolCallingManagers = toolCallingManagers;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry == null ? null : meterRegistry.getIfAvailable();
        this.enabled = enabled;
        this.maxPerInvocation = maxPerInvocation;
        this.holdBackChars = holdBackChars;
    }

    public boolean enabled() {
        return enabled;
    }

    /** {@code model} wrapped with recovery, or {@code model} itself when recovery is off. */
    public ChatModel wrap(ChatModel model) {
        if (!enabled || model == null || model instanceof TextToolCallRecoveringChatModel) {
            return model;
        }
        return new TextToolCallRecoveringChatModel(
                model,
                toolCallingManagers.create(),
                objectMapper,
                maxPerInvocation,
                holdBackChars,
                meterRegistry);
    }
}
