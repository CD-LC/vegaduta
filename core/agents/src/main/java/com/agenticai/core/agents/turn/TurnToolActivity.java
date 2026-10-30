package com.agenticai.core.agents.turn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * What ONE chat turn's tools have done so far, shared by reference between the tool wrapper that
 * reports it ({@code LoggingToolCallback}, the single place every tool call of a turn passes
 * through) and the turn loop that must reason about it. The model-and-tool loop runs inside Spring
 * AI, so the application cannot see a tool call start or end from the stream; this is the record of
 * it.
 *
 * <p>Two decisions depend on it:
 *
 * <ul>
 *   <li><b>The stream idle timer.</b> A tool that runs for minutes emits no token, so a timer that
 *       counts only tokens declares the provider dead while a tool is simply working. {@link
 *       #inFlightCount()} and {@link #lastActivityNanos()} let the timer treat tool time as
 *       activity.
 *   <li><b>Replay.</b> Failing a turn over to another provider re-runs it from the start, tools
 *       included. "No text was streamed yet" does not mean "nothing happened": a tool-calling turn
 *       commonly emits no text while it works. {@link #snapshot()} says which tools ran, how, and
 *       whether the effect journal recorded their effect (a repeat of an identical journaled call
 *       is served from the journal instead of reaching the provider again).
 * </ul>
 *
 * <p>Thread-safe: tools run on Reactor and virtual threads, the loop reads from others. One
 * instance per turn, held by {@link TurnContext}; the clock is injectable for tests.
 */
public final class TurnToolActivity {

    /** How one tool call ended, from the point of view of "did it act on the outside world". */
    public enum Outcome {
        /**
         * The tool ran and returned; the effect journal did not record it (a read, or the journal
         * is off).
         */
        EXECUTED,
        /**
         * The tool ran and returned, and the effect journal recorded its result: a repeat is served
         * from it.
         */
        JOURNALED,
        /**
         * The tool threw after being reached; a failure after the provider acted looks like one
         * before it.
         */
        FAILED,
        /** Served from the effect journal: this call did not reach the tool again. */
        REPLAYED,
        /**
         * A guard answered instead (approval, policy, step budget, validation): the tool did not
         * run.
         */
        NOT_RUN
    }

    /** Per-tool tally of finished calls. */
    public record Tally(int executed, int journaled, int failed, int replayed, int notRun) {

        /** Calls that reached the tool: its effect, if any, has happened. */
        public int reachedTool() {
            return executed + journaled + failed;
        }

        /**
         * Calls that reached the tool and left no journal record: a repeat could act a second time.
         */
        public int unjournaled() {
            return executed + failed;
        }
    }

    /**
     * A point-in-time copy: finished calls per tool (in first-call order) and the tools still
     * running.
     */
    public record Snapshot(Map<String, Tally> finished, List<String> inFlight) {

        public Snapshot {
            finished = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(finished));
            inFlight = List.copyOf(inFlight);
        }

        /** True when any tool reached its implementation or is still running. */
        public boolean anyToolRan() {
            return !inFlight.isEmpty()
                    || finished.values().stream().anyMatch(t -> t.reachedTool() > 0);
        }
    }

    private final LongSupplier nanoClock;
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicLong lastActivity;
    private final Map<Long, String> running = new LinkedHashMap<>();
    private final Map<String, int[]> finished = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final TurnToolLedger ledger = new TurnToolLedger();
    private final com.agenticai.core.guardrails.taint.TurnTaintSeeds taintSeeds =
            new com.agenticai.core.guardrails.taint.TurnTaintSeeds();

    /** Prompt-time content that must taint the turn once tool resolution knows its taint key. */
    public com.agenticai.core.guardrails.taint.TurnTaintSeeds taintSeeds() {
        return taintSeeds;
    }

    public TurnToolActivity() {
        this(System::nanoTime);
    }

    /** Test seam: a controllable clock (nanoseconds, monotonic). */
    public TurnToolActivity(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.lastActivity = new AtomicLong(nanoClock.getAsLong());
    }

    /**
     * The compact record of what this turn's tools did (tool, arguments, outcome), written by the
     * tool wrapper as each call finishes and stored with the assistant turn; see {@link
     * ToolLedger}.
     */
    public TurnToolLedger ledger() {
        return ledger;
    }

    /** A tool call is about to reach its tool; returns the id to pass to {@link #end}. */
    public long begin(String tool) {
        long id = nextId.incrementAndGet();
        synchronized (this) {
            running.put(id, tool == null ? "?" : tool);
        }
        touch();
        return id;
    }

    /** The call {@code id} finished as {@code outcome}. */
    public void end(long id, Outcome outcome) {
        synchronized (this) {
            String tool = running.remove(id);
            if (tool != null) {
                finished.computeIfAbsent(tool, k -> new int[Outcome.values().length])[
                        outcome.ordinal()]++;
            }
        }
        touch();
    }

    /**
     * Marks activity now without a start or end: a long tool that can report progress calls this.
     */
    public void touch() {
        lastActivity.set(nanoClock.getAsLong());
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // a listener is an optimisation for a timer; it must never break a tool call
            }
        }
    }

    /**
     * Calls {@code listener} whenever a tool starts, finishes or reports progress, so a timer can
     * re-check instead of waiting out a stale deadline. Returns the call that removes it.
     */
    public Runnable onActivity(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Tools running right now. */
    public synchronized int inFlightCount() {
        return running.size();
    }

    /** When a tool last started, finished or reported progress ({@code System.nanoTime} scale). */
    public long lastActivityNanos() {
        return lastActivity.get();
    }

    /** The clock this tracker runs on, so a timer compares against the same time base. */
    public long nowNanos() {
        return nanoClock.getAsLong();
    }

    /** True when any tool reached its implementation or is still running. */
    public boolean anyToolRan() {
        return snapshot().anyToolRan();
    }

    /**
     * How many tool calls have finished so far this turn, whatever became of them (ran, failed,
     * replayed from the journal, answered by a guard). Read before and after a model pass, the
     * difference is "the model called tools in this pass": the turn loop's evidence that a pass
     * made progress, which needs no database read and no clock.
     */
    public synchronized int finishedCalls() {
        int total = 0;
        for (int[] counts : finished.values()) {
            for (int count : counts) {
                total += count;
            }
        }
        return total;
    }

    public synchronized Snapshot snapshot() {
        Map<String, Tally> tallies = new LinkedHashMap<>();
        finished.forEach(
                (tool, c) ->
                        tallies.put(
                                tool,
                                new Tally(
                                        c[Outcome.EXECUTED.ordinal()],
                                        c[Outcome.JOURNALED.ordinal()],
                                        c[Outcome.FAILED.ordinal()],
                                        c[Outcome.REPLAYED.ordinal()],
                                        c[Outcome.NOT_RUN.ordinal()])));
        return new Snapshot(tallies, new ArrayList<>(running.values()));
    }
}
