package com.agenticai.core.agents.resumable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.resumable.ResumableStreamBuffer.Entry;
import com.agenticai.core.agents.resumable.ResumableStreamBuffer.ReadResult;
import com.agenticai.core.agents.resumable.ResumableStreamBuffer.TurnMeta;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The byte budgets of the per-JVM buffer: a per-turn cap that drops the oldest entries, and a node-wide cap. */
class InMemoryResumableStreamBufferByteBudgetTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private static TurnMeta meta(String turnId) {
        return new TurnMeta(turnId, "t1", "alice", UUID.randomUUID().toString(), "session-1", 0L);
    }

    private static String chars(int n) {
        return "x".repeat(n);
    }

    private static long weight(String payload) {
        return InMemoryResumableStreamBuffer.weigh(new Entry(1L, payload, false));
    }

    @Test
    void aTurnOverItsByteBudgetDropsItsOldestEntriesAndAReaderBehindThemIsToldSo() {
        // room for about three 1000-char entries
        long perTurn = weight(chars(1000)) * 3 + 10;
        InMemoryResumableStreamBuffer buffer = new InMemoryResumableStreamBuffer(
            5000, 600, 900, now::get, perTurn, Long.MAX_VALUE);
        buffer.open(meta("turn-1"));
        for (int i = 0; i < 6; i++) {
            buffer.append("turn-1", chars(1000));
        }

        ReadResult fromStart = buffer.read("t1", "turn-1", 0L);
        assertTrue(fromStart.found());
        assertTrue(fromStart.gap(), "the head was dropped, so a replay from 0 cannot be complete");

        // a reader that kept up still gets the tail
        ReadResult tail = buffer.read("t1", "turn-1", 5L);
        assertFalse(tail.gap());
        assertEquals(1, tail.entries().size());
        assertEquals(6L, tail.entries().get(0).id());
    }

    @Test
    void theNewestEntryIsKeptEvenWhenItAloneExceedsTheBudget() {
        InMemoryResumableStreamBuffer buffer = new InMemoryResumableStreamBuffer(
            5000, 600, 900, now::get, 100L, Long.MAX_VALUE);
        buffer.open(meta("turn-1"));
        long id = buffer.append("turn-1", chars(10_000));

        ReadResult read = buffer.read("t1", "turn-1", id - 1L);
        assertFalse(read.gap());
        assertEquals(1, read.entries().size());
    }

    @Test
    void finishedTurnsAreEvictedWhenTheNodeBudgetIsExceededButARunningTurnIsNot() {
        long budget = weight(chars(1000)) * 3;
        InMemoryResumableStreamBuffer buffer = new InMemoryResumableStreamBuffer(
            5000, 600, 900, now::get, Long.MAX_VALUE, budget);
        buffer.open(meta("done-1"));
        buffer.append("done-1", chars(1000));
        buffer.append("done-1", chars(1000));
        buffer.finish("done-1", "complete");
        buffer.open(meta("running"));
        buffer.append("running", chars(1000));
        buffer.append("running", chars(1000));
        assertTrue(buffer.totalBytes() > budget);

        // the next turn to open sweeps (the budget is exceeded): the finished turn goes, the running one stays
        now.addAndGet(2_000L);
        buffer.open(meta("next"));

        assertFalse(buffer.read("t1", "done-1", 0L).found());
        assertTrue(buffer.read("t1", "running", 0L).found());
    }

    @Test
    void bytesAreGivenBackWhenATurnExpires() {
        InMemoryResumableStreamBuffer buffer = new InMemoryResumableStreamBuffer(
            5000, 10, 10, now::get, Long.MAX_VALUE, Long.MAX_VALUE);
        buffer.open(meta("turn-1"));
        buffer.append("turn-1", chars(500));
        buffer.finish("turn-1", "complete");
        assertTrue(buffer.totalBytes() > 0L);

        now.addAndGet(60_000L);
        assertFalse(buffer.read("t1", "turn-1", 0L).found());
        assertEquals(0L, buffer.totalBytes());
    }
}
