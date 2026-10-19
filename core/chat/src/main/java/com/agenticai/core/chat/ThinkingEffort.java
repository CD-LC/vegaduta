package com.agenticai.core.chat;

import java.util.Locale;
import java.util.Set;

/**
 * The per-agent Anthropic extended-thinking effort (low | medium | high) as it would cross from
 * agent config to the provider factory. No agent field feeds it today (a first-class one needs an
 * {@code agent.thinking_effort VARCHAR(16) NULL} column, owned by the schema codebase); until then the
 * platform-level {@code app.llm.anthropic.thinking.effort} is the only source. Carried in {@code providerOptions} under
 * {@link #OPTION_KEY} (the same key {@link AnthropicThinking#resolveEffort} reads); only the
 * Anthropic factory looks at it.
 */
public final class ThinkingEffort {

    /** {@code providerOptions} key. */
    public static final String OPTION_KEY = "reasoningEffort";

    private static final Set<String> ALLOWED = Set.of("low", "medium", "high");

    private ThinkingEffort() {
    }

    /** low | medium | high (lower-cased, trimmed), or null for null, blank or anything else. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return ALLOWED.contains(v) ? v : null;
    }
}
