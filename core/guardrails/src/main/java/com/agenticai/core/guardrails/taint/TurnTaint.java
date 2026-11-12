package com.agenticai.core.guardrails.taint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Taint state for ONE agent turn: has any content an outsider could have authored entered the
 * model's context during this turn, and from where.
 *
 * <p>One instance is shared by every tool wrapper built for the turn (the same sharing the step
 * counter relies on). It is a plain thread-safe object with no thread-local state, so the tool
 * calls Spring AI dispatches on other threads see exactly what the originating thread recorded -
 * nothing to propagate, nothing that can leak into a pooled thread. Taint only ever grows within a
 * turn; there is deliberately no way to clear it.
 */
public final class TurnTaint {

    /** Bound on remembered source labels; the turn stays tainted regardless. */
    static final int MAX_SOURCES = 8;

    /** Bound on distinct observe-mode reports per turn. */
    static final int MAX_REPORTED = 32;

    private final List<String> sources = new ArrayList<>(2);
    private final Set<String> reported = new HashSet<>();
    private final List<Runnable> listeners = new ArrayList<>(1);
    private volatile boolean tainted;
    private volatile Consumer<List<String>> listener;

    /**
     * Records that content from {@code source} (a tool name or channel label) entered the turn.
     *
     * @return true if this call is the one that tainted a previously clean turn
     */
    public boolean mark(String source) {
        String label = source == null || source.isBlank() ? "unknown" : source;
        boolean first;
        boolean changed;
        List<String> snapshot = null;
        List<Runnable> fire = List.of();
        synchronized (sources) {
            first = !tainted;
            tainted = true;
            changed = first;
            if (sources.size() < MAX_SOURCES && !sources.contains(label)) {
                sources.add(label);
                changed = true;
            }
            if (changed && listener != null) {
                snapshot = List.copyOf(sources);
            }
            if (first) {
                fire = List.copyOf(listeners);
                listeners.clear();
            }
        }
        fire.forEach(TurnTaint::runQuietly);
        Consumer<List<String>> l = listener;
        if (snapshot != null && l != null) {
            try {
                l.accept(snapshot);
            } catch (RuntimeException e) {
                // A persistence hook must never break the turn that is being tainted.
            }
        }
        return first;
    }

    /**
     * Runs {@code action} once, when this turn first becomes tainted - at once if it already is,
     * otherwise on whichever thread marks it. The action must not rely on thread-local state; a
     * throw is swallowed so a mark can never fail.
     */
    public void onTainted(Runnable action) {
        if (action == null) {
            return;
        }
        synchronized (sources) {
            if (!tainted) {
                listeners.add(action);
                return;
            }
        }
        runQuietly(action);
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // a listener must never break marking
        }
    }

    /**
     * Registers the single hook told whenever the set of sources changes (the first mark, and each
     * new source label after it) with a snapshot. The chat pipeline uses it to persist a session
     * level taint marker (see {@code SessionTaintStore}) so a later turn of the same conversation
     * starts tainted. Called on the marking thread, outside the lock; exceptions are swallowed.
     * Replaces any earlier hook.
     */
    public void onChange(Consumer<List<String>> hook) {
        this.listener = hook;
    }

    /**
     * True the first time {@code key} is reported on this turn, false afterwards. Observe mode uses
     * it so a tainted turn writes one WARN and one audit row per tool, not one per call. Bounded:
     * past {@link #MAX_REPORTED} distinct keys nothing more is reported (the meter still counts).
     */
    public boolean firstReport(String key) {
        synchronized (reported) {
            return reported.size() < MAX_REPORTED && reported.add(key == null ? "unknown" : key);
        }
    }

    public boolean isTainted() {
        return tainted;
    }

    /**
     * Starts this (child) turn with what {@code parent} knows at this moment: every source label
     * the parent recorded, and the tainted flag. A delegated, specialist or team-branch turn calls
     * this once when it is created, so it can never be cleaner than the turn that handed it work.
     * It is a copy, not a link: marks the parent or a sibling gets later do not reach this turn,
     * and marks this turn gets do not reach the parent (the parent reads the child's answer through
     * the delegating tool, which is itself a taint source). No-op for a null or clean parent.
     */
    public void inheritFrom(TurnTaint parent) {
        if (parent == null || parent == this || !parent.isTainted()) {
            return;
        }
        List<String> inherited = parent.sources();
        if (inherited.isEmpty()) {
            mark("delegation");
            return;
        }
        for (String source : inherited) {
            mark(source);
        }
    }

    /** Snapshot of the (bounded) sources that tainted this turn, in order of first appearance. */
    public List<String> sources() {
        synchronized (sources) {
            return List.copyOf(sources);
        }
    }
}
