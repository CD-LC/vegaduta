package com.agenticai.core.agents.turn;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the {@link ToolLedger} of ONE turn as its tools finish, shared by reference between the tool
 * wrapper that records ({@code LoggingToolCallback}, the single place every tool call of a turn passes
 * through) and the turn code that stores the reply. Held by {@link TurnToolActivity}, so it travels with
 * the same per-turn object the tool wrappers already report to.
 *
 * <p>Thread-safe: tools finish on Reactor and virtual threads. A call past {@code maxEntries} is counted,
 * not listed.
 */
public final class TurnToolLedger {

    private final List<ToolLedger.Entry> entries = new ArrayList<>();
    private int omitted;

    /** Adds {@code entry}, unless {@code maxEntries} are already listed (then it is only counted). */
    public synchronized void record(ToolLedger.Entry entry, int maxEntries) {
        if (entry == null) {
            return;
        }
        if (entries.size() >= Math.max(0, maxEntries)) {
            omitted++;
            return;
        }
        entries.add(entry);
    }

    /** What has been recorded so far; {@link ToolLedger#EMPTY} when nothing was. */
    public synchronized ToolLedger snapshot() {
        return entries.isEmpty() && omitted == 0 ? ToolLedger.EMPTY : new ToolLedger(entries, omitted);
    }

    /** The snapshot as a stored turn carries it: {@code null} when nothing was recorded. */
    public ToolLedger snapshotOrNull() {
        return ToolLedger.nullIfNone(snapshot());
    }
}
