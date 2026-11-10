package com.agenticai.core.agents.turn;

import java.util.Locale;

/**
 * How much the platform adds to what an agent sends the model ({@code chat.prompt.profile}, per
 * workspace or per agent). Guardrails, approvals and audit run outside the prompt and are the same
 * in every profile.
 *
 * <ul>
 *   <li>{@link #FULL}: the standing directives, the skills index, recalled memory and the standing
 *       tools (memory, skills, restore) - the behaviour before this setting existed.
 *   <li>{@link #LEAN}: the same pieces in their short wording.
 *   <li>{@link #BARE}: the agent's own system prompt, procedures, custom instructions and the tools
 *       its owner chose (allowed tools, attached tool servers), and nothing the platform would add
 *       on its own.
 * </ul>
 */
public enum PromptProfile {
    FULL,
    LEAN,
    BARE;

    /** The profile named by {@code value} (any case); {@link #FULL} for null, blank or unknown. */
    public static PromptProfile parse(String value) {
        if (value == null || value.isBlank()) {
            return FULL;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return FULL;
        }
    }

    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
