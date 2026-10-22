package com.agenticai.core.chat;

import com.openai.models.completions.CompletionUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;

/**
 * Reasoning ("thinking") output tokens of one LLM call, normalized across the providers that report
 * them and bill them differently.
 *
 * <p>Verified against the pinned Spring AI jars (1.1.7 on 2026-10-02; re-based on the Spring AI 2.0
 * SDK usage objects, whose native usage is {@code CompletionUsage} / {@code
 * com.anthropic.models.messages.Usage}):
 *
 * <ul>
 *   <li><b>Google GenAI</b> - {@code GoogleGenAiUsage.from} maps {@code completionTokens} from
 *       {@code candidatesTokenCount} ONLY; {@code thoughtsTokenCount} is a separate, additional
 *       bucket that Google bills at the OUTPUT rate. Before this class those tokens were in no cost
 *       estimate, so every Gemini thinking call was under-priced (and under-budgeted). They are
 *       {@link #billableReasoningTokens()}.
 *   <li><b>OpenAI</b> - {@code completion_tokens_details.reasoning_tokens} is a breakdown OF {@code
 *       completionTokens}, already priced at the output rate. Reported for observability only;
 *       billable count is {@code null} (adding it would double-bill). Exception: an
 *       OpenAI-compatible endpoint whose {@code total_tokens} exceeds prompt + completion by at
 *       least the reasoning count has demonstrably excluded them from completion; those are treated
 *       as additional (see {@link #reportedOutsideCompletion}).
 *   <li><b>Anthropic</b> - extended-thinking tokens are folded into {@code output_tokens} and the
 *       API reports no separate count, so the result is {@link #ABSENT} (already priced via output
 *       tokens).
 * </ul>
 *
 * Same absent-is-not-zero rule as {@link CacheTokenAccounting}.
 *
 * @param reasoningTokens reasoning tokens the provider reported, or {@code null}
 * @param includedInCompletionTokens whether {@code reasoningTokens} is already part of {@code
 *     completionTokens} (OpenAI) or additional to it (Gemini)
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
                return thoughts == null
                        ? ABSENT
                        : new ReasoningTokenAccounting(thoughts.longValue(), false);
            }
            Object nativeUsage = usage.getNativeUsage();
            if (nativeUsage instanceof CompletionUsage openAiUsage) {
                Long reasoning =
                        openAiUsage
                                .completionTokensDetails()
                                .flatMap(CompletionUsage.CompletionTokensDetails::reasoningTokens)
                                .orElse(null);
                if (reasoning == null) {
                    return ABSENT;
                }
                return new ReasoningTokenAccounting(
                        reasoning, !reportedOutsideCompletion(openAiUsage, reasoning));
            }
            if (nativeUsage instanceof com.anthropic.models.messages.Usage) {
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
     * {@code total - prompt - completion} is at least the reasoning count, the reasoning tokens are
     * demonstrably outside completion and must be billed on top.
     */
    private static boolean reportedOutsideCompletion(CompletionUsage usage, long reasoning) {
        if (reasoning <= 0) {
            return false;
        }
        return usage.totalTokens() - usage.promptTokens() - usage.completionTokens() >= reasoning;
    }

    public boolean isPresent() {
        return reasoningTokens != null;
    }

    /**
     * Reasoning tokens to price ON TOP of completion tokens; {@code null} when already inside them
     * or unknown.
     */
    public Long billableReasoningTokens() {
        return includedInCompletionTokens ? null : reasoningTokens;
    }
}
