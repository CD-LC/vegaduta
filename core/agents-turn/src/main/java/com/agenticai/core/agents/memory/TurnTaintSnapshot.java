package com.agenticai.core.agents.memory;

import com.agenticai.core.guardrails.taint.TurnTaint;
import java.util.List;

/**
 * An immutable, read-only copy of a finished turn's taint, taken when the turn ends and handed to the turn-completion
 * observers (ADR-0028 WP4c). The live {@link TurnTaint} is mutable and belongs to the turn; an observer that runs
 * later, on another thread, reads this instead and cannot change the turn's record.
 *
 * <p>Fail closed: a snapshot built from no record, or from one that throws, is unreadable and reports itself tainted
 * with the {@link MemoryWriteGate} "taint-state-unavailable" label, so {@link MemoryWriteGate} quarantines.
 */
public final class TurnTaintSnapshot implements TurnTaintView {

    private static final TurnTaintSnapshot UNREADABLE =
            new TurnTaintSnapshot(false, true, List.of(MemoryWriteGate.UNAVAILABLE));
    private static final TurnTaintSnapshot CLEAN = new TurnTaintSnapshot(true, false, List.of());

    private final boolean readable;
    private final boolean tainted;
    private final List<String> sources;

    private TurnTaintSnapshot(boolean readable, boolean tainted, List<String> sources) {
        this.readable = readable;
        this.tainted = tainted;
        this.sources = sources;
    }

    /** The snapshot used when the taint state cannot be read. */
    public static TurnTaintSnapshot unreadable() {
        return UNREADABLE;
    }

    /** Copies {@code taint} as it is now; null or a throwing record gives {@link #unreadable()}. */
    public static TurnTaintSnapshot of(TurnTaint taint) {
        if (taint == null) {
            return UNREADABLE;
        }
        try {
            if (!taint.isTainted()) {
                return CLEAN;
            }
            List<String> labels = taint.sources();
            return new TurnTaintSnapshot(
                    true, true, labels == null || labels.isEmpty() ? List.of("unknown") : List.copyOf(labels));
        } catch (RuntimeException e) {
            return UNREADABLE;
        }
    }

    /** False when the turn's record could not be read (then {@link #isTainted()} is true). */
    public boolean readable() {
        return readable;
    }

    @Override
    public boolean isTainted() {
        return tainted;
    }

    @Override
    public List<String> sources() {
        return sources;
    }
}
