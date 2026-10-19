package com.agenticai.core.guardrails;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Interface for implementing a specific content guardrail. Each implementation
 * checks for a particular category of harmful or policy-violating content.
 *
 * <p><b>Contract:</b> Implementations MUST be stateless and thread-safe, as
 * they are used concurrently across many chat turns and tenants.
 *
 * <p><b>Integration:</b> Used by {@link ContentGuardrailEngine} to evaluate
 * content on both input (user messages) and output (agent responses) paths.
 * Invoked selectively based on per-agent configuration and plan tier.
 */
public interface ContentGuardrail {

    /**
     * Returns the type of guardrail this implements.
     */
    ContentGuardrailType getType();

    /**
     * Evaluates a piece of content against this guardrail.
     *
     * @param content the text to check
     * @return a {@link ContentGuardrailResult} indicating pass/fail and reason
     */
    ContentGuardrailResult evaluate(String content);

    /**
     * Evaluates a piece of content with optional semantic analysis via a
     * ChatClient. Default implementation ignores the {@code chatClient} and
     * delegates to {@link #evaluate(String)}.
     *
     * <p>Implementations that need semantic/model-based checks (e.g., toxicity
     * detection) can override this method.
     *
     * @param content the text to check
     * @param chatClient optional ChatClient for semantic analysis; may be null
     * @return a {@link ContentGuardrailResult} indicating pass/fail and reason
     */
    default ContentGuardrailResult evaluate(String content, ChatClient chatClient) {
        return evaluate(content);
    }

    /**
     * Evaluates content knowing WHICH direction it is travelling in. Default
     * implementation ignores {@code path} and delegates to
     * {@link #evaluate(String, ChatClient)}, so every pre-existing guardrail is
     * unaffected.
     *
     * <p>Exists because the two paths differ in what a guardrail is allowed to
     * do, not just in what it checks: the agent-output path can hand a
     * rewritten string back to the caller (see
     * {@link ContentGuardrailResult#redact}), whereas the user-input path runs
     * before the model client and the response cache exist and has nowhere to
     * put a rewrite. A guardrail that can redact must therefore know not to
     * offer a redaction that would be silently discarded - see
     * {@code CustomRuleContentGuardrail}.
     *
     * @param path INPUT or OUTPUT; may be null when the caller did not supply
     *     an evaluation context, which implementations must treat as "assume
     *     nothing, check only"
     */
    default ContentGuardrailResult evaluate(
        String content, ChatClient chatClient, ContentGuardrailEngine.GuardrailEvaluationPath path
    ) {
        return evaluate(content, chatClient);
    }

    /**
     * Returns true if this guardrail requires a ChatClient for full effectiveness
     * (i.e., semantic/model-based checks). When false, the guardrail works fine
     * with just pattern-based or heuristic checks.
     *
     * <p>Used by {@link ContentGuardrailEngine} to avoid building a ChatClient
     * if no guardrails need it.
     */
    default boolean requiresChatClient() {
        return false;
    }
}
