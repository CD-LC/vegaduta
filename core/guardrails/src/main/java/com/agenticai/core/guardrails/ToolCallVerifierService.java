package com.agenticai.core.guardrails;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Agent Intelligence research doc (July 2026), Part 1 - an independent
 * "evaluator" check on a tool call before it's allowed to execute,
 * separate from the agent that decided to make the call. This is
 * Anthropic's/Spring AI's Evaluator-Optimizer pattern applied at the
 * tool-call boundary rather than the final-answer boundary: cheaper (one
 * short classification call, not a second full response) and catches the
 * failure mode that matters most for an autonomous agent - "the model
 * decided to do something the user didn't actually ask for" - before any
 * side effect happens, not after.
 *
 * <p>Only invoked by {@link GuardedToolCallback} when the calling agent's
 * {@code reasoningDepth} is {@code RIGOROUS} - see that enum's javadoc for
 * why {@code FAST} (the default) skips this entirely. One interface,
 * swappable implementation, same house style as {@link GuardrailService}.
 */
public interface ToolCallVerifierService {

    /**
     * @param chatClient a pre-built {@code ChatClient} already configured
     *     with the calling agent's own provider/model/credentials (built
     *     once per chat turn in {@code AgentChatService}, threaded down
     *     through {@code AgentToolProvider} - this method never resolves
     *     its own credentials, it reuses the caller's) - a synchronous,
     *     non-streaming call is made against it, deliberately separate from
     *     the turn's own streamed response.
     * @param userMessage the user's original message this turn, for the
     *     verifier to judge relevance against.
     * @param toolName the tool the agent is about to call.
     * @param toolInput the exact arguments the agent is about to pass.
     * @return {@link VerificationResult#approved} if the call plausibly
     *     matches user intent, {@link VerificationResult#rejected}
     *     otherwise. Never throws for an ordinary "this looks wrong"
     *     judgment - a rejection is fed back to the model as a tool result
     *     it can react to (ask for clarification, try something else), not
     *     a hard stop. On the verifier's own internal errors (model
     *     unreachable, blank answer) an implementation follows {@code
     *     app.guardrails.tool-verifier.fail-mode}, which defaults to the
     *     global {@code app.guardrails.fail-mode} (closed: the call is
     *     refused). Set it to {@code open} where a verifier outage must not
     *     stop RIGOROUS agents' tool calls.
     */
    VerificationResult verify(ChatClient chatClient, String userMessage, String toolName, String toolInput);

    /** The end of the conversation, asked for lazily and only by a verifier that has a use for it. */
    @FunctionalInterface
    interface ConversationTail {

        /** At most {@code maxMessages} messages, oldest first, within {@code maxChars}; empty when none. */
        String get(int maxMessages, int maxChars);
    }

    /**
     * As {@link #verify(ChatClient, String, String, String)} with the end of the conversation available as
     * context: a short follow-up ("yes, send it") can only be judged against what it answers. The default
     * ignores {@code conversationTail}, so an implementation that does not use it is unchanged.
     */
    default VerificationResult verify(
        ChatClient chatClient, String userMessage, ConversationTail conversationTail, String toolName, String toolInput
    ) {
        return verify(chatClient, userMessage, toolName, toolInput);
    }
}
