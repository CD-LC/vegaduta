package com.agenticai.core.quality.dataset;

import java.util.Locale;
import java.util.Optional;

/**
 * How an eval run replays its cases.
 *
 * <ul>
 *   <li>{@link #FULL} (the default): the production pipeline. Same reasoning depth, tool selection, guards, approval
 *       gate, step ceilings and budget as a person's message, in a sandbox: a tool that could change data or reach
 *       outside the platform is answered with a simulated result and never runs, a call that needs a human is held
 *       without notifying anyone (or approved by the sandbox's auto-approval where the operator allows it), and the
 *       run is metered against its budget. This is what the agent does for its users, so this is what the eval
 *       measures.
 *   <li>{@link #QUICK}: the agent's tools are withheld and the model can only answer in text. Cheaper and faster, and
 *       it says nothing about tool use; chosen explicitly, per run or as a dataset's or workspace's standing choice.
 * </ul>
 *
 * The default is the platform property {@code app.quality.eval.default-mode} and the workspace setting {@code
 * quality.eval.defaultMode}; a dataset may pin its own mode, and a run may name one, in that order of precedence
 * (run, then dataset, then workspace, then platform).
 */
public enum EvalMode {
    FULL,
    QUICK;

    /** Whether the agent's tools are offered to the turn. */
    public boolean executesTools() {
        return this == FULL;
    }

    /**
     * The mode named by {@code value} (case-insensitive), or empty when it is null or blank.
     *
     * @throws IllegalArgumentException for any other text, naming the two valid values
     */
    public static Optional<EvalMode> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'mode' must be FULL or QUICK, not '" + value.trim() + "'.");
        }
    }

    /** The mode a stored value names, or {@code fallback} for null or unreadable text (a stored row never throws). */
    public static EvalMode lenient(String value, EvalMode fallback) {
        try {
            return parse(value).orElse(fallback);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
