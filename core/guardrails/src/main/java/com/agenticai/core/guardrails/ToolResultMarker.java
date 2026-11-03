package com.agenticai.core.guardrails;

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
 * <p>{@link #isPlatformAnswer} no longer accepts the old wording prefixes ("This tool call (", ...): a tool's output is
 * attacker-influenced text, and one that began with those words was read as the platform's own refusal and left out of
 * the effect and turn journals. A producer must register its text with {@link #mark}; the two typed control results
 * ({@link ApprovalParked}, {@link ToolBudgetExhausted}) are also recognised by their exact two-field JSON shape.
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

    /**
     * Identity of ONE turn for this registry. The registry is keyed by text, and a text is not unique to a turn or a
     * tenant: two turns whose tools answer with the same words would share a mark, so one turn's refusal text could make
     * another turn's genuine tool output look like the platform's answer (and hide a real call from the journals). A
     * mark registered under a scope is visible to readers of that scope only. The scope is compared by identity and held
     * weakly, so a finished turn's marks are collected with it.
     *
     * <p>A turn owns one ({@link TurnApprovalState#markerScope()}). It reaches {@link #mark(String, Kind)} and {@link
     * #kindOf(String)} through {@link #bind(Scope)} on the thread running the turn's tools (and re-bound on any thread
     * the work is handed to, like the tenant), or explicitly through the overloads that take it.
     */
    public static final class Scope {
        public Scope() {}
    }

    /** Marks registered with no scope (producers not migrated, tests): visible to every reader, as before scopes existed. */
    private static final Scope UNSCOPED = new Scope();

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    /** Text -> scope -> kind. Weak on both the text and the scope. Guarded by its own monitor. */
    private static final Map<String, Map<Scope, Kind>> MARKED = new WeakHashMap<>();

    /** Makes {@code scope} the ambient scope of the calling thread until the returned handle is closed. */
    public static Binding bind(Scope scope) {
        Scope previous = CURRENT.get();
        if (scope == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(scope);
        }
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** The scope bound to the calling thread, or {@code null}. */
    public static Scope currentScope() {
        return CURRENT.get();
    }

    /** Undoes a {@link #bind}. */
    @FunctionalInterface
    public interface Binding extends AutoCloseable {
        @Override
        void close();
    }

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

    /**
     * As {@link #markGuardOutcome(String, String)} for the turn {@code state} belongs to: the mark is registered under
     * the turn's {@link TurnApprovalState#markerScope() scope}, and a step-limit answer also sets the turn's
     * out-of-band budget signal ({@link TurnApprovalState#isBudgetExhausted()}) that the tool loop reads. This is what
     * {@link GuardedToolCallback} should call.
     */
    static String markGuardOutcome(String result, String outcome, TurnApprovalState state) {
        if (state == null) {
            return markGuardOutcome(result, outcome);
        }
        if (GuardedToolCallback.OUTCOME_STEP_LIMIT.equals(outcome)) {
            state.markBudgetExhausted();
        }
        try (Binding ignored = bind(state.markerScope())) {
            return markGuardOutcome(result, outcome);
        }
    }

    /** Registers {@code result} as the platform's answer of {@code kind} under the ambient scope; returns {@code result} itself. */
    public static String mark(String result, Kind kind) {
        Scope ambient = CURRENT.get();
        return mark(result, kind, ambient == null ? UNSCOPED : ambient);
    }

    /** As {@link #mark(String, Kind)}, visible to readers of {@code scope} only ({@code null} = every reader). */
    public static String mark(String result, Kind kind, Scope scope) {
        if (result != null && kind != null) {
            synchronized (MARKED) {
                MARKED.computeIfAbsent(result, k -> new WeakHashMap<>()).put(scope == null ? UNSCOPED : scope, kind);
            }
        }
        return result;
    }

    /**
     * The registered kind of exactly this text. Under an ambient scope only that scope's marks and unscoped ones count;
     * with no scope bound, any mark counts (the pre-scope behaviour, which cross-turn collisions can fool).
     */
    public static Optional<Kind> kindOf(String result) {
        return kindOf(result, CURRENT.get());
    }

    /** As {@link #kindOf(String)} for an explicit {@code scope} ({@code null} = unbound reader). */
    public static Optional<Kind> kindOf(String result, Scope scope) {
        if (result == null) {
            return Optional.empty();
        }
        synchronized (MARKED) {
            Map<Scope, Kind> marks = MARKED.get(result);
            if (marks == null || marks.isEmpty()) {
                return Optional.empty();
            }
            if (scope == null) {
                Kind unscoped = marks.get(UNSCOPED);
                return Optional.ofNullable(unscoped != null ? unscoped : marks.values().iterator().next());
            }
            Kind own = marks.get(scope);
            return Optional.ofNullable(own != null ? own : marks.get(UNSCOPED));
        }
    }

    /**
     * True when {@code result} is the platform's answer and not a tool's output: it was {@link #mark registered},
     * or it is exactly a budget-exhausted or approval-parked control result.
     */
    public static boolean isPlatformAnswer(String result) {
        if (result == null) {
            return false;
        }
        if (kindOf(result).isPresent()) {
            return true;
        }
        if (CURRENT.get() != null) {
            // A turn is bound: its platform answers were registered under its scope, so a text that only has the SHAPE
            // of a control result is a tool's output (any tool can print it).
            return false;
        }
        return ToolBudgetExhausted.isBudgetExhausted(result) || ApprovalParked.isParked(result);
    }

    /**
     * The kind of a platform answer, for a reader that needs to tell a parked call from a refused one: the registered
     * kind, else the kind of an exact control result (a budget-exhausted result is {@link Kind#BUDGET_EXHAUSTED}, a
     * parked one {@link Kind#PARKED}); empty when {@code result} is a tool's output or an unregistered text.
     */
    public static Optional<Kind> classify(String result) {
        Optional<Kind> registered = kindOf(result);
        if (registered.isPresent()) {
            return registered;
        }
        if (result == null || CURRENT.get() != null) {
            return Optional.empty();
        }
        if (ToolBudgetExhausted.isBudgetExhausted(result)) {
            return Optional.of(Kind.BUDGET_EXHAUSTED);
        }
        if (ApprovalParked.isParked(result)) {
            return Optional.of(Kind.PARKED);
        }
        return Optional.empty();
    }
}
