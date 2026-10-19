package com.agenticai.core.chat;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * Optional per-run hook into the Spring AI tool loop: a caller that wants to reshape the conversation
 * the model is about to be re-sent (tool-result clearing, context compaction) places an implementation
 * in the request's {@code ToolContext} under {@link #TOOL_CONTEXT_KEY}
 * ({@code ChatClient.Builder#defaultToolContext}), and {@link ArgumentSanitizingToolCallingManager}
 * applies it to the conversation history after every tool round.
 *
 * <p>Why the tool context and not a bean: the manager is a process-wide singleton and the loop runs on
 * a reactive/HTTP thread with no {@code TenantContext}, so everything the transform needs (the
 * tenant snapshot, the model window, the step-event emitter) has to travel with the one run it belongs
 * to. A request that does not carry the key sees none of this - the manager's behaviour is unchanged.
 *
 * <p>Contract for implementations: pure with respect to the input list (return it untouched when there
 * is nothing to change, never mutate it), must not throw (the manager also guards, and falls back to
 * the untouched history), and must keep tool-call / tool-response pairing valid.
 */
@FunctionalInterface
public interface ToolLoopHistoryTransform {

    /** Key in {@code ToolCallingChatOptions#getToolContext()} under which a run's transform travels. */
    String TOOL_CONTEXT_KEY = "vegaduta.toolLoopHistoryTransform";

    /**
     * @param history the conversation the next model round would be sent, system messages included
     * @param modelId the model the next round targets, or {@code null} when the options do not say
     * @return the history to send instead; the same instance when nothing changed
     */
    List<Message> transform(List<Message> history, String modelId);
}
