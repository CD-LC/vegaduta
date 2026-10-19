package com.agenticai.core.guardrails;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Autonomy/Guardrails Strategy Stage A - the seam every tool's output
 * passes through before it re-enters the model's context (wired in via
 * {@link GuardedToolCallback}, one per resolved tool, same decorator
 * pattern {@code LoggingToolCallback} already established). One interface,
 * swappable implementation - same house style as {@code
 * PaymentGatewayClient}/{@code ProviderValidator} elsewhere in this
 * codebase. Exactly one implementation is active at a time, selected by
 * {@code app.guardrails.implementation} ({@code semantic} default as of
 * Master-Roadmap-2026.md item #7, or {@code pattern} as an explicit
 * lighter-weight opt-out - see both implementations' {@code
 * @ConditionalOnProperty}).
 *
 * <p>{@link PatternBasedGuardrailService} is a deliberately modest V1: regex
 * checks for the cheapest, most common cases (secret-shaped strings,
 * blatant prompt-injection markers), not a claim of solving prompt
 * injection or data-loss prevention outright.
 *
 * <p>{@link SemanticGuardrailService} is the "stronger implementation... later work" the
 * paragraph above used to point at - see that class's javadoc for why it
 * ended up built directly on Spring AI's {@code ChatClient} rather than
 * adopting {@code langchain4j-guardrails} as originally planned, and for why
 * it is now the default rather than an opt-in.
 */
public interface GuardrailService {

    /**
     * Inspects and, if needed, redacts a tool's raw output before it's
     * returned to the model.
     *
     * @param toolName the tool that produced this output, for logging/error
     *     messages only
     * @param rawOutput the tool's unmodified return value
     * @return the output to actually hand back to the model - identical to
     *     {@code rawOutput} unless something needed redacting
     * @throws GuardrailViolationException if the output should be withheld
     *     entirely rather than passed through in any form
     */
    String sanitizeToolOutput(String toolName, String rawOutput);

    /**
     * Same contract as {@link #sanitizeToolOutput(String, String)}, plus an
     * optional {@code chatClient} an implementation MAY use for a semantic
     * (not just pattern-matched) check - see {@link SemanticGuardrailService}.
     * Default implementation just ignores {@code chatClient} and delegates
     * to the two-arg method, so {@link PatternBasedGuardrailService} (and
     * any other implementation that has no use for a model call) needs no
     * changes at all.
     *
     * @param chatClient the calling turn's already-built {@code ChatClient}
     *     (same credentials/provider/model the main response uses, see
     *     {@code ToolCallVerifierService#verify}'s javadoc for the identical
     *     "reuse, don't re-resolve" reasoning) - nullable, and every
     *     implementation must treat null the same as "no semantic check
     *     available this call" rather than erroring.
     */
    default String sanitizeToolOutput(String toolName, String rawOutput, ChatClient chatClient) {
        return sanitizeToolOutput(toolName, rawOutput);
    }
}
