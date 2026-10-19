package com.agenticai.core.chat;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Maps the platform's reasoning-effort vocabulary ({@code none|low|medium|high}, the same one the
 * Gemini thinking mapping in {@link HostedLlmChatClientFactory} uses) to Anthropic extended
 * thinking {@code budget_tokens}. Pure functions, no Spring.
 *
 * <p>Anthropic constraints this encodes: {@code budget_tokens} must be at least 1024 and strictly
 * below {@code max_tokens}; while thinking is on, {@code temperature}, {@code top_p} and
 * {@code top_k} must be left unset (the factory skips them, see
 * {@link AnthropicAgentChatClientFactory#samplingBuilder}); only some model families support
 * manual budgets, so the effort is ignored for any model not matching {@link #SUPPORTED_MODEL}.
 *
 * <p>Scope note: the per-call key {@link #OPTION_KEY} is honoured here but no caller
 * sets it yet (the agent table has no thinking-effort field; see {@link ThinkingEffort}); the admin
 * setting {@code platform.hosted-llm.gemini-reasoning-effort} is Gemini-specific and is NOT mapped
 * to Anthropic; the operator default is {@code app.llm.anthropic.thinking.effort}. Reasoning-token
 * pricing/metrics are likewise not part of this change.
 *
 * <p><b>Thinking on tool-carrying turns is deliberately NOT supported</b> (see
 * {@link AnthropicThinkingChatModel}): Anthropic requires the signed thinking blocks of an
 * assistant turn that contains {@code tool_use} to be passed back unchanged with the tool result,
 * and Spring AI 1.1.7's {@code AnthropicChatModel} rebuilds that assistant message from text and
 * tool_use only, so the signature blocks are lost and the follow-up request is a 400. There is no
 * hook in the {@code ChatModel}/{@code ToolCallingManager} abstraction to carry them, so no
 * {@code with-tools} flag exists; a per-agent effort therefore only thinks on tool-free turns.
 */
final class AnthropicThinking {

    /** {@code providerOptions} key a caller may use to pass a per-call effort. */
    static final String OPTION_KEY = ThinkingEffort.OPTION_KEY;

    static final int MIN_BUDGET = 1024;

    /** Upper bound on a budget regardless of effort or max_tokens. */
    static final int MAX_BUDGET = 24576;

    /**
     * Exact model families verified to accept {@code thinking: {type: enabled, budget_tokens}}:
     * the family name optionally followed by a {@code -YYYYMMDD} snapshot or {@code -latest}.
     * Deliberately NOT a prefix match - newer 4.x models (e.g. sonnet-4-6, opus-4-6 and later)
     * are unverified and may reject manual budgets, so they stay off until added here.
     */
    static final Pattern SUPPORTED_MODEL = Pattern.compile(
        "^claude-(3-7-sonnet|sonnet-4|sonnet-4-5|opus-4|opus-4-1|opus-4-5|haiku-4-5)(-\\d{8}|-latest)?$");

    private AnthropicThinking() {
    }

    /** The per-call effort from {@code providerOptions}, falling back to {@code configured}; null = off. */
    static String resolveEffort(Map<String, Object> providerOptions, String configured) {
        if (providerOptions != null && providerOptions.get(OPTION_KEY) instanceof String s && !s.isBlank()) {
            return s;
        }
        return configured;
    }

    /**
     * The {@code budget_tokens} to send, or null when thinking must stay off: effort null, blank,
     * unknown or {@code none}; model unsupported; or {@code maxTokens} too small to leave room for
     * a reply after a 1024-token budget. A larger budget is clamped to half of {@code maxTokens}
     * (so it is always below max_tokens and the visible answer has room) and to {@link #MAX_BUDGET}.
     */
    static Integer budgetFor(String model, String effort, int maxTokens) {
        if (effort == null || model == null) {
            return null;
        }
        String lower = model.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_MODEL.matcher(lower).matches()) {
            return null;
        }
        int budget = switch (effort.trim().toLowerCase(Locale.ROOT)) {
            case "low" -> MIN_BUDGET;
            case "medium" -> 8192;
            case "high" -> MAX_BUDGET;
            default -> 0;
        };
        if (budget == 0) {
            return null;
        }
        int ceiling = maxTokens / 2;
        if (ceiling < MIN_BUDGET) {
            return null;
        }
        return Math.min(Math.min(budget, ceiling), MAX_BUDGET);
    }
}
