package com.agenticai.core.chat;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * Marks a request whose CALLER, not the platform, owns the tool loop: the model's tool calls must
 * come back as data instead of being executed and looped on (the IDE plugins' client-side agent
 * turn, the forced-tool structured-output call, the constrained tool router's own rounds).
 *
 * <p>Spring AI 1.x expressed this as {@code internalToolExecutionEnabled(false)} on the options.
 * Spring AI 2 removed the flag: the model never runs tools, the {@code ChatClient}'s {@code
 * ToolCallingAdvisor} does. The marker therefore travels in the options' {@code toolContext} (never
 * sent to the provider) and is honoured by {@link CancellationAwareToolCallingAdvisor} (which then
 * does not loop) and by the model wrappers that would otherwise run a tool loop of their own
 * ({@link TextToolCallRecoveringChatModel}, {@link ConstrainedToolRouterChatModel}).
 */
public final class CallerRunsToolLoop {

    /** {@code toolContext} key carrying the marker. */
    public static final String CONTEXT_KEY = "vegaduta.callerRunsToolLoop";

    private CallerRunsToolLoop() {}

    /** Marks the options being built; returns the same builder. */
    public static <B extends ToolCallingChatOptions.Builder<?>> B mark(B builder) {
        builder.toolContext(CONTEXT_KEY, Boolean.TRUE);
        return builder;
    }

    /** True when {@code options} carry the marker. */
    public static boolean isSet(ChatOptions options) {
        return options instanceof ToolCallingChatOptions tool
                && tool.getToolContext() != null
                && Boolean.TRUE.equals(tool.getToolContext().get(CONTEXT_KEY));
    }
}
