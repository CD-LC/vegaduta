package com.agenticai.core.guardrails.taint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private volatile boolean tainted;

    /**
     * Records that content from {@code source} (a tool name or channel label) entered the turn.
     *
     * @return true if this call is the one that tainted a previously clean turn
     */
    public boolean mark(String source) {
        String label = source == null || source.isBlank() ? "unknown" : source;
        synchronized (sources) {
            boolean first = !tainted;
            tainted = true;
            if (sources.size() < MAX_SOURCES && !sources.contains(label)) {
                sources.add(label);
            }
            return first;
        }
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

    /** Snapshot of the (bounded) sources that tainted this turn, in order of first appearance. */
    public List<String> sources() {
        synchronized (sources) {
            return List.copyOf(sources);
        }
    }
}
