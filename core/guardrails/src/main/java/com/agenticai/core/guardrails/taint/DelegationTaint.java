package com.agenticai.core.guardrails.taint;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Carries the delegating turn's {@link TurnTaint} to the sub-turn its tool call starts, so a
 * delegate, specialist or team branch begins with everything its parent had read and can never be
 * cleaner than it.
 *
 * <p>Why a scope. A delegation tool ({@code ask_agent_*}, {@code delegate_to_specialist}, {@code
 * delegate_parallel}, {@code run_subtasks}) is called by the model inside the parent's guarded tool
 * chain, and the code behind it has no handle on the parent's turn. The guard opens {@link
 * #openCall} around the tool's {@code call()} with the parent's taint; the sub-turn builder reads
 * it with {@link #childOfCurrent()} and gets a new {@code TurnTaint} that starts as a copy. The
 * scope is a thread-local like {@code ToolCallScope}: it is only ever read on the thread that
 * opened it, closed in a {@code finally}, and nests (a child's own tool call opens its own, so a
 * grandchild inherits from the child). A fan-out onto other threads captures the scope with {@link
 * #propagating} on the originating thread.
 */
public final class DelegationTaint {

    private static final ThreadLocal<TurnTaint> CALL = new ThreadLocal<>();
    private static final ThreadLocal<TurnTaint> RESOLVING = new ThreadLocal<>();

    private DelegationTaint() {}

    /** Restores the enclosing binding on close. */
    public static final class Scope implements AutoCloseable {
        private final ThreadLocal<TurnTaint> slot;
        private final TurnTaint previous;

        private Scope(ThreadLocal<TurnTaint> slot, TurnTaint previous) {
            this.slot = slot;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                slot.remove();
            } else {
                slot.set(previous);
            }
        }
    }

    /**
     * The tool call of {@code parent} runs on this thread until the scope is closed. A null parent
     * hides an outer one.
     */
    public static Scope openCall(TurnTaint parent) {
        return open(CALL, parent);
    }

    /** The taint of the turn whose tool call is running on this thread, or empty outside one. */
    public static Optional<TurnTaint> currentCall() {
        return Optional.ofNullable(CALL.get());
    }

    /**
     * A new taint for a sub-turn starting now: a copy of the calling turn's (clean when there is no
     * calling turn or it is clean).
     */
    public static TurnTaint childOfCurrent() {
        TurnTaint child = new TurnTaint();
        child.inheritFrom(CALL.get());
        return child;
    }

    /**
     * The sub-turn's own taint while its tools are resolved, so the tools' gate and the turn share
     * one instance.
     */
    public static Scope openResolving(TurnTaint child) {
        return open(RESOLVING, child);
    }

    /** The taint a sub-turn announced for the tools being resolved on this thread, if any. */
    public static Optional<TurnTaint> resolving() {
        return Optional.ofNullable(RESOLVING.get());
    }

    /**
     * Wraps {@code work} so that, wherever it runs, it sees the calling turn's taint as captured
     * NOW on this thread. For fan-out onto an executor: each branch then copies the parent's state
     * when it starts.
     */
    public static <T> Callable<T> propagating(Callable<T> work) {
        TurnTaint captured = CALL.get();
        return () -> {
            try (Scope ignored = openCall(captured)) {
                return work.call();
            }
        };
    }

    private static Scope open(ThreadLocal<TurnTaint> slot, TurnTaint value) {
        TurnTaint previous = slot.get();
        if (value == null) {
            slot.remove();
        } else {
            slot.set(value);
        }
        return new Scope(slot, previous);
    }
}
