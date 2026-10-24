package com.agenticai.core.guardrails;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Says, as a typed {@link Kind}, that a tool result is the PLATFORM's answer to a call and not the tool's output: a
 * guard refused the call, it was parked for a human, a policy withheld the output, or the turn's tool budget was used
 * up. In every one of those cases the tool did not run (or its output was not returned), so nothing downstream may
 * treat the call as an executed effect: the effect journal must not record it, the turn journal must not list it as a
 * successful call.
 *
 * <p>A tool callback can only return a {@code String}, so the answer cannot carry a type itself. Until now the
 * consumers recognised these answers by the first words of their text ("This tool call (", "This action (", ...),
 * which is wrong in two directions: a legitimate output that starts with those words was taken for a refusal, and a
 * refusal whose wording is configured or wrapped (the approval-park text is {@code app.guardrails.approval-park.*}; an
 * MCP refusal is wrapped into a JSON object) was taken for an executed call. Now the layer that produces the answer
 * {@link #mark registers} the exact text it returns, and a consumer asks {@link #kindOf}. The registry holds its keys
 * weakly, so a finished turn's texts are collected with it.
 *
 * <p>{@link #isPlatformAnswer} also accepts the old wording prefixes, so an answer from a producer that has not
 * registered itself (a plugin, a test double, a deployment that overrides the text through a path not migrated) is still
 * recognised exactly as before.
 */
public final class ToolResultMarker {

    /** Why the platform answered instead of the tool. */
    public enum Kind {
        /** A guard, policy, hook, argument check or circuit breaker refused the call. */
        BLOCKED,
        /** The call was held for a human decision; it has not run. */
        PARKED,
        /** The tool ran, but a content or tenant policy withheld its output from the model. */
        WITHHELD,
        /** The turn's tool budget was used up; the call was answered, not run. */
        BUDGET_EXHAUSTED,
        /** An offline eval turn's sandbox answered with a simulated result instead of running the tool. */
        SANDBOXED
    }

    private static final Map<String, Kind> MARKED = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The wording prefixes the platform's own refusals have always started with. Kept so an unregistered producer is
     * still recognised; new code registers its text with {@link #mark} instead of relying on them.
     */
    private static final String[] LEGACY_PREFIXES = {
        "This tool call (",
        "This tool call failed",
        "This action (",
        "This tool (",
        "Tool output from '"
    };

    private ToolResultMarker() {}

    /**
     * Registers what {@link GuardedToolCallback} answered with, from its outcome code: every outcome but success is
     * the guard replying in place of the tool (refused, parked for a human, budget used up) or withholding its
     * output. Returns {@code result} itself; a success is left unmarked.
     */
    static String markGuardOutcome(String result, String outcome) {
        Kind kind = switch (outcome) {
            case GuardedToolCallback.OUTCOME_APPROVAL_PENDING -> Kind.PARKED;
            case GuardedToolCallback.OUTCOME_STEP_LIMIT -> Kind.BUDGET_EXHAUSTED;
            case GuardedToolCallback.OUTCOME_OUTPUT_WITHHELD -> Kind.WITHHELD;
            case GuardedToolCallback.OUTCOME_SANDBOXED -> Kind.SANDBOXED;
            // A returned (not thrown) "error" is the park that could not be recorded.
            case GuardedToolCallback.OUTCOME_BLOCKED, GuardedToolCallback.OUTCOME_ERROR -> Kind.BLOCKED;
            default -> null;
        };
        return kind == null ? result : mark(result, kind);
    }

    /** Registers {@code result} as the platform's answer of {@code kind}; returns {@code result} itself. */
    public static String mark(String result, Kind kind) {
        if (result != null && kind != null) {
            MARKED.put(result, kind);
        }
        return result;
    }

    /** The registered kind of exactly this text, or empty when no producer registered it. */
    public static Optional<Kind> kindOf(String result) {
        if (result == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(MARKED.get(result));
    }

    /**
     * True when {@code result} is the platform's answer and not a tool's output: it was {@link #mark registered}, it is
     * a structural-validation failure or a budget-exhausted result, or it starts with one of the old refusal prefixes.
     */
    public static boolean isPlatformAnswer(String result) {
        if (result == null) {
            return false;
        }
        if (kindOf(result).isPresent()) {
            return true;
        }
        for (String prefix : LEGACY_PREFIXES) {
            if (result.startsWith(prefix)) {
                return true;
            }
        }
        return result.startsWith(ToolCallOutputValidator.VALIDATION_FAILURE_PREFIX)
                || ToolBudgetExhausted.isBudgetExhausted(result);
    }

    /**
     * The kind of a platform answer, for a reader that needs to tell a parked call from a refused one: the registered
     * kind, else a kind inferred from the old wording ({@code This action (} is the park text, a budget-exhausted
     * result is {@link Kind#BUDGET_EXHAUSTED}, any other platform answer is {@link Kind#BLOCKED}); empty when
     * {@code result} is a tool's output.
     */
    public static Optional<Kind> classify(String result) {
        Optional<Kind> registered = kindOf(result);
        if (registered.isPresent()) {
            return registered;
        }
        if (result == null) {
            return Optional.empty();
        }
        if (ToolBudgetExhausted.isBudgetExhausted(result)) {
            return Optional.of(Kind.BUDGET_EXHAUSTED);
        }
        if (result.startsWith("This action (")) {
            return Optional.of(Kind.PARKED);
        }
        if (result.startsWith("Tool output from '")) {
            return Optional.of(Kind.WITHHELD);
        }
        return isPlatformAnswer(result) ? Optional.of(Kind.BLOCKED) : Optional.empty();
    }
}
