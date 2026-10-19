package com.agenticai.core.agents.hooks;

import java.util.Locale;

/**
 * The six places in an agent run where a declarative hook may fire. The wire
 * form is the enum name ({@code BEFORE_TOOL}); parsing is case-insensitive.
 *
 * <p>Vocabulary follows the usual agent-middleware shape (LangChain middleware,
 * Claude Code hooks, OpenAI Agents SDK guardrail hooks) so a team that already
 * thinks in those terms can read a hook list at a glance.
 */
public enum HookPoint {

    /** A chat turn is about to call the model (after input guardrails and caches, before any provider spend). */
    BEFORE_MODEL,
    /** The turn's complete reply is about to be delivered. Opt-in buffering when a transforming hook is attached. */
    AFTER_MODEL,
    /** A tool call is about to run (arguments known). */
    BEFORE_TOOL,
    /** A tool call returned (the model-facing, already-sanitised result). */
    AFTER_TOOL,
    /** A tool call or the run failed. Informational: log and webhook only. */
    ON_ERROR,
    /** The run's stream settled (complete, error or cancel). Informational: log and webhook only. */
    ON_RUN_END;

    public boolean isToolPoint() {
        return this == BEFORE_TOOL || this == AFTER_TOOL;
    }

    public boolean isModelPoint() {
        return this == BEFORE_MODEL || this == AFTER_MODEL;
    }

    /** @return the matching point, or {@code null} when the text names none. */
    public static HookPoint parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
