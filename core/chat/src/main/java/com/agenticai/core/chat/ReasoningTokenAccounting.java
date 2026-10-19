package com.agenticai.core.chat;

import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * Reasoning ("thinking") output tokens of one LLM call, normalized across the
 * providers that report them and bill them differently.
 *
 * <p>Verified against the pinned Spring AI 1.1.7 jars (2026-10-02):
 * <ul>
 *   <li><b>Google GenAI</b> - {@code GoogleGenAiUsage.from} maps
 *       {@code completionTokens} from {@code candidatesTokenCount} ONLY;
 *       {@code thoughtsTokenCount} is a separate, additional bucket that
 *       Google bills at the OUTPUT rate. Before this class those tokens were
 *       in no cost estimate, so every Gemini thinking call was under-priced
 *       (and under-budgeted). They are {@link #billableReasoningTokens()}.</li>
 *   <li><b>OpenAI</b> - {@code completion_tokens_details.reasoning_tokens} is a
 *       breakdown OF {@code completionTokens}, already priced at the output
 *       rate. Reported for observability only; billable count is {@code null}
 *       (adding it would double-bill). Exception: an OpenAI-compatible
 *       endpoint whose {@code total_tokens} exceeds prompt + completion by at
 *       least the reasoning count has demonstrably excluded them from
 *       completion; those are treated as additional (see
 *       {@link #reportedOutsideCompletion}).</li>
 *   <li><b>Anthropic</b> - extended-thinking tokens are folded into
 *       {@code output_tokens} and the API reports no separate count, so the
 *       result is {@link #ABSENT} (already priced via output tokens).</li>
 * </ul>
 * Same absent-is-not-zero rule as {@link CacheTokenAccounting}.
 *
 * @param reasoningTokens reasoning tokens the provider reported, or {@code null}
 * @param includedInCompletionTokens whether {@code reasoningTokens} is already
 *     part of {@code completionTokens} (OpenAI) or additional to it (Gemini)
 */
public record ReasoningTokenAccounting(Long reasoningTokens, boolean includedInCompletionTokens) {

    public static final ReasoningTokenAccounting ABSENT = new ReasoningTokenAccounting(null, false);

    public static ReasoningTokenAccounting from(Usage usage) {
        if (usage == null) {
            return ABSENT;
        }
        try {
            if (usage instanceof GoogleGenAiUsage googleUsage) {
                Integer thoughts = googleUsage.getThoughtsTokenCount();
                return thoughts == null ? ABSENT : new ReasoningTokenAccounting(thoughts.longValue(), false);
            }
            Object nativeUsage = usage.getNativeUsage();
            if (nativeUsage instanceof OpenAiApi.Usage openAiUsage) {
                OpenAiApi.Usage.CompletionTokenDetails details = openAiUsage.completionTokenDetails();
                Integer reasoning = details == null ? null : details.reasoningTokens();
                if (reasoning == null) {
                    return ABSENT;
                }
                return new ReasoningTokenAccounting(
                    reasoning.longValue(), !reportedOutsideCompletion(openAiUsage, reasoning));
            }
            if (nativeUsage instanceof AnthropicApi.Usage) {
                // Extended-thinking tokens are billed as output tokens and are already counted in
                // output_tokens (Spring AI maps that to completionTokens), so a thinking-on call is
                // priced correctly through the output rate; there is no separate bucket to add.
                return ABSENT;
            }
        } catch (RuntimeException e) {
            return ABSENT;
        }
        return ABSENT;
    }

    /**
     * OpenAI proper reports {@code total = prompt + completion} with reasoning tokens a subset of
     * completion. Some OpenAI-compatible endpoints that front a thinking model (Gemini's
     * OpenAI-compat layer is the known one) instead report {@code completion} WITHOUT the thoughts
     * and put them only in {@code total} and in {@code reasoning_tokens}; trusting the "subset"
     * rule there would leave those tokens unpriced. The usage block itself says which it is: when
     * {@code total - prompt - completion} is at least the reasoning count, the reasoning tokens
     * are demonstrably outside completion and must be billed on top.
     */
    private static boolean reportedOutsideCompletion(OpenAiApi.Usage usage, int reasoning) {
        Integer prompt = usage.promptTokens();
        Integer completion = usage.completionTokens();
        Integer total = usage.totalTokens();
        if (reasoning <= 0 || prompt == null || completion == null || total == null) {
            return false;
        }
        return (long) total - prompt - completion >= reasoning;
    }

    public boolean isPresent() {
        return reasoningTokens != null;
    }

    /** Reasoning tokens to price ON TOP of completion tokens; {@code null} when already inside them or unknown. */
    public Long billableReasoningTokens() {
        return includedInCompletionTokens ? null : reasoningTokens;
    }
}
