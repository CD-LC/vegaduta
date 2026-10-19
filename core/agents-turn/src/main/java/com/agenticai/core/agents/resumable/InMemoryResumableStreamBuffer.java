package com.agenticai.core.agents.resumable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Per-JVM {@link ResumableStreamBuffer}. Always the source of truth on the node that is
 * generating a turn; with {@code app.chat.resumable-streams.store=redis} it is additionally
 * mirrored to Redis by {@link MirroredResumableStreamBuffer} so another replica can serve a
 * resume. Memory is bounded five ways: {@code maxEvents} entries per turn (oldest dropped),
 * an estimated byte budget per turn (oldest dropped; a single event can be a whole buffered answer),
 * a TTL after completion, {@link #MAX_TURNS} turns in total, and a byte budget for the whole node
 * (finished turns are evicted first; a turn that is still running is never evicted to make room).
 *
 * <p>Time comes from an injected clock so expiry is testable without sleeping.
 */
final class InMemoryResumableStreamBuffer implements ResumableStreamBuffer {

    /** Hard ceiling on buffered turns on one node, independent of config. */
    static final int MAX_TURNS = 5000;

    private static final long MIN_SWEEP_INTERVAL_MS = 1_000L;

    /** Default per-turn heap budget (an estimate, see {@link #weigh}): oldest entries are dropped past it. */
    static final long DEFAULT_MAX_BYTES_PER_TURN = 4L * 1024 * 1024;
    /** Default budget for every turn on this node together. */
    static final long DEFAULT_MAX_TOTAL_BYTES = 256L * 1024 * 1024;

    private final int maxEvents;
    private final long maxBytesPerTurn;
    private final long maxTotalBytes;
    /** Estimated heap held by every turn's entries right now. */
    private final AtomicLong totalBytes = new AtomicLong();
    private final long ttlMillis;
    /** How long a RUNNING turn's buffer is kept alive past its last append (a leak guard, not a feature). */
    private final long runningTtlMillis;
    private final LongSupplier nowMillis;
    private final Map<String, Turn> turns = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepAt = new AtomicLong(Long.MIN_VALUE);

    private static final class Turn {
        private final TurnMeta meta;
        private final ArrayDeque<Entry> entries = new ArrayDeque<>();
        private long nextId = 1L;
        private boolean finished;
        private long bytes;
        /** Set once the turn was removed from the map: late appends must not be counted into the node total. */
        private boolean forgotten;
        private volatile long expiresAtMs;

        private Turn(TurnMeta meta, long expiresAtMs) {
            this.meta = meta;
            this.expiresAtMs = expiresAtMs;
        }
    }

    InMemoryResumableStreamBuffer(int maxEvents, long ttlSeconds, long detachTimeoutSeconds, LongSupplier nowMillis) {
        this(maxEvents, ttlSeconds, detachTimeoutSeconds, nowMillis, DEFAULT_MAX_BYTES_PER_TURN, DEFAULT_MAX_TOTAL_BYTES);
    }

    InMemoryResumableStreamBuffer(
        int maxEvents, long ttlSeconds, long detachTimeoutSeconds, LongSupplier nowMillis, long maxBytesPerTurn, long maxTotalBytes
    ) {
        this.maxBytesPerTurn = Math.max(1L, maxBytesPerTurn);
        this.maxTotalBytes = Math.max(1L, maxTotalBytes);
        this.maxEvents = Math.max(1, maxEvents);
        this.ttlMillis = Math.max(1L, ttlSeconds) * 1000L;
        this.runningTtlMillis = (Math.max(1L, detachTimeoutSeconds) * 1000L) + this.ttlMillis;
        this.nowMillis = nowMillis;
    }

    @Override
    public void open(TurnMeta meta) {
        sweep();
        turns.computeIfAbsent(meta.turnId(), id -> new Turn(meta, nowMillis.getAsLong() + runningTtlMillis));
    }

    @Override
    public long append(String turnId, String payload) {
        Turn turn = live(turnId);
        if (turn == null) {
            return -1L;
        }
        synchronized (turn) {
            if (turn.finished) {
                return -1L;
            }
            if (turn.forgotten) {
                return -1L;
            }
            long id = turn.nextId++;
            addEntry(turn, new Entry(id, payload == null ? "" : payload, false));
            trim(turn);
            turn.expiresAtMs = nowMillis.getAsLong() + runningTtlMillis;
            return id;
        }
    }

    @Override
    public long finish(String turnId, String status) {
        Turn turn = live(turnId);
        if (turn == null) {
            return -1L;
        }
        synchronized (turn) {
            if (turn.finished) {
                Entry last = turn.entries.peekLast();
                return last == null ? -1L : last.id();
            }
            if (turn.forgotten) {
                return -1L;
            }
            long id = turn.nextId++;
            addEntry(turn, new Entry(id, status == null ? "complete" : status, true));
            trim(turn);
            turn.finished = true;
            turn.expiresAtMs = nowMillis.getAsLong() + ttlMillis;
            return id;
        }
    }

    @Override
    public Optional<TurnMeta> meta(String tenantId, String turnId) {
        Turn turn = liveFor(tenantId, turnId);
        return turn == null ? Optional.empty() : Optional.of(turn.meta);
    }

    @Override
    public ReadResult read(String tenantId, String turnId, long afterId) {
        Turn turn = liveFor(tenantId, turnId);
        if (turn == null) {
            return ReadResult.notFound();
        }
        long from = Math.max(0L, afterId);
        synchronized (turn) {
            Entry first = turn.entries.peekFirst();
            if (first == null) {
                return ReadResult.of(List.of());
            }
            // Dropped entries sit between the caller's cursor and the oldest one retained: say so.
            if (from + 1L < first.id()) {
                return ReadResult.gapped();
            }
            // Newest first and stop at the cursor: a reader that is keeping up (the common case) touches only the
            // few entries it has not seen, not the whole buffer, under the lock the producer also needs.
            List<Entry> out = new ArrayList<>();
            Iterator<Entry> newestFirst = turn.entries.descendingIterator();
            while (newestFirst.hasNext()) {
                Entry entry = newestFirst.next();
                if (entry.id() <= from) {
                    break;
                }
                out.add(entry);
            }
            Collections.reverse(out);
            return ReadResult.of(out);
        }
    }

    /** Number of turns currently held (expired ones not yet swept included). Test seam. */
    int size() {
        return turns.size();
    }

    private void addEntry(Turn turn, Entry entry) {
        turn.entries.addLast(entry);
        long weight = weigh(entry);
        turn.bytes += weight;
        totalBytes.addAndGet(weight);
    }

    /**
     * Drops the OLDEST entries past either bound: the event count, or the turn's byte budget (buffered reasoning
     * depths emit a whole answer as ONE event, so a count alone says little about heap). The newest entry is always
     * kept. A reader whose cursor fell behind is told so by {@link ReadResult#gap()}, as for the count bound.
     */
    private void trim(Turn turn) {
        while (turn.entries.size() > maxEvents || (turn.bytes > maxBytesPerTurn && turn.entries.size() > 1)) {
            long weight = weigh(turn.entries.removeFirst());
            turn.bytes -= weight;
            totalBytes.addAndGet(-weight);
        }
    }

    /** Rough heap cost of one entry: UTF-16 chars plus object overhead. An estimate, not an exact measurement. */
    static long weigh(Entry entry) {
        return 2L * entry.payload().length() + 48L;
    }

    /** Removes the turn from the map (if it is still the mapped one) and gives its bytes back to the node total. */
    private void forget(String turnId, Turn turn) {
        if (!turns.remove(turnId, turn)) {
            return;
        }
        synchronized (turn) {
            turn.forgotten = true;
            totalBytes.addAndGet(-turn.bytes);
            turn.bytes = 0L;
        }
    }

    /** Estimated heap currently held by buffered entries on this node. Test seam. */
    long totalBytes() {
        return totalBytes.get();
    }

    /** The turn, or null when unknown or expired (an expired one is dropped on the spot). */
    private Turn live(String turnId) {
        if (turnId == null) {
            return null;
        }
        Turn turn = turns.get(turnId);
        if (turn == null) {
            return null;
        }
        if (nowMillis.getAsLong() >= turn.expiresAtMs) {
            forget(turnId, turn);
            return null;
        }
        return turn;
    }

    /** Like {@link #live}, but a turn that belongs to another tenant is invisible. */
    private Turn liveFor(String tenantId, String turnId) {
        Turn turn = live(turnId);
        if (turn == null || !java.util.Objects.equals(turn.meta.tenantId(), tenantId)) {
            return null;
        }
        return turn;
    }

    private void sweep() {
        long now = nowMillis.getAsLong();
        long previous = lastSweepAt.get();
        boolean due = previous == Long.MIN_VALUE || now - previous >= MIN_SWEEP_INTERVAL_MS || turns.size() >= MAX_TURNS
            || totalBytes.get() > maxTotalBytes;
        if (!due || !lastSweepAt.compareAndSet(previous, now)) {
            return;
        }
        for (Map.Entry<String, Turn> e : new ArrayList<>(turns.entrySet())) {
            if (now >= e.getValue().expiresAtMs) {
                forget(e.getKey(), e.getValue());
            }
        }
        if (turns.size() < MAX_TURNS && totalBytes.get() <= maxTotalBytes) {
            return;
        }
        // Still full: make room by dropping finished turns, soonest-to-expire first. A running turn is never
        // evicted - losing it would break a stream that is actively being read.
        List<Map.Entry<String, Turn>> finished = new ArrayList<>();
        for (Map.Entry<String, Turn> e : turns.entrySet()) {
            synchronized (e.getValue()) {
                if (e.getValue().finished) {
                    finished.add(e);
                }
            }
        }
        finished.sort((a, b) -> Long.compare(a.getValue().expiresAtMs, b.getValue().expiresAtMs));
        // Until both the turn count and the node byte budget are back inside their bounds (or nothing finished is left).
        for (int i = 0; i < finished.size() && (turns.size() >= MAX_TURNS || totalBytes.get() > maxTotalBytes); i++) {
            forget(finished.get(i).getKey(), finished.get(i).getValue());
        }
    }
}
