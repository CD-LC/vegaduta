package com.agenticai.core.agents.resumable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.resumable.ResumableStreamBuffer.Entry;
import com.agenticai.core.agents.resumable.ResumableStreamBuffer.ReadResult;
import com.agenticai.core.agents.resumable.ResumableStreamBuffer.TurnMeta;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Replay ordering, after-id semantics, bounding, TTL expiry and tenant isolation of the per-JVM buffer. */
class InMemoryResumableStreamBufferTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private InMemoryResumableStreamBuffer buffer(int maxEvents, long ttlSeconds, long detachSeconds) {
        return new InMemoryResumableStreamBuffer(maxEvents, ttlSeconds, detachSeconds, now::get);
    }

    private static TurnMeta meta(String turnId, String tenantId) {
        return new TurnMeta(turnId, tenantId, "alice", UUID.randomUUID().toString(), "session-1", 0L);
    }

    private static List<Long> ids(ReadResult result) {
        return result.entries().stream().map(Entry::id).toList();
    }

    private static List<String> payloads(ReadResult result) {
        return result.entries().stream().map(Entry::payload).toList();
    }

    @Test
    void idsAreMonotonicFromOneAndReplayKeepsOrder() {
        InMemoryResumableStreamBuffer buffer = buffer(100, 600, 900);
        buffer.open(meta("turn-1", "t1"));

        assertEquals(1L, buffer.append("turn-1", "a"));
        assertEquals(2L, buffer.append("turn-1", "b"));
        assertEquals(3L, buffer.append("turn-1", "c"));

        ReadResult all = buffer.read("t1", "turn-1", 0L);
        assertTrue(all.found());
        assertFalse(all.gap());
        assertEquals(List.of(1L, 2L, 3L), ids(all));
        assertEquals(List.of("a", "b", "c"), payloads(all));
    }

    @Test
    void afterIdIsExclusive() {
        InMemoryResumableStreamBuffer buffer = buffer(100, 600, 900);
        buffer.open(meta("turn-1", "t1"));
        buffer.append("turn-1", "a");
        buffer.append("turn-1", "b");
        buffer.append("turn-1", "c");

        assertEquals(List.of(3L), ids(buffer.read("t1", "turn-1", 2L)));
        // Caught up: nothing new, but the turn is found and not gapped.
        ReadResult caughtUp = buffer.read("t1", "turn-1", 3L);
        assertTrue(caughtUp.found());
        assertFalse(caughtUp.gap());
        assertTrue(caughtUp.entries().isEmpty());
        // A cursor ahead of the buffer reads as "nothing yet", never as an error.
        assertTrue(buffer.read("t1", "turn-1", 99L).entries().isEmpty());
        // A negative cursor reads from the start.
        assertEquals(List.of(1L, 2L, 3L), ids(buffer.read("t1", "turn-1", -5L)));
    }

    @Test
    void finishAppendsOneTerminalEntryAndRejectsFurtherAppends() {
        InMemoryResumableStreamBuffer buffer = buffer(100, 600, 900);
        buffer.open(meta("turn-1", "t1"));
        buffer.append("turn-1", "a");

        long terminalId = buffer.finish("turn-1", "complete");
        assertEquals(2L, terminalId);
        // Idempotent: a second finish reports the same terminal entry and adds nothing.
        assertEquals(2L, buffer.finish("turn-1", "error"));
        assertEquals(-1L, buffer.append("turn-1", "late"));

        ReadResult all = buffer.read("t1", "turn-1", 0L);
        assertEquals(2, all.entries().size());
        Entry terminal = all.entries().get(1);
        assertTrue(terminal.terminal());
        assertEquals("complete", terminal.payload());
    }

    @Test
    void boundedBufferDropsOldestAndFlagsAStaleCursorAsAGap() {
        InMemoryResumableStreamBuffer buffer = buffer(3, 600, 900);
        buffer.open(meta("turn-1", "t1"));
        for (String chunk : List.of("a", "b", "c", "d", "e", "f")) {
            buffer.append("turn-1", chunk);
        }

        // Retained: 4, 5, 6. A cursor at 3 needs 4 next - still contiguous.
        ReadResult fresh = buffer.read("t1", "turn-1", 3L);
        assertFalse(fresh.gap());
        assertEquals(List.of(4L, 5L, 6L), ids(fresh));
        assertEquals(List.of("d", "e", "f"), payloads(fresh));

        // A cursor at 2 needs 3, which is gone: the replay cannot be complete and must say so.
        assertTrue(buffer.read("t1", "turn-1", 2L).gap());
        assertTrue(buffer.read("t1", "turn-1", 0L).gap());
    }

    @Test
    void aFinishedTurnExpiresAfterTheTtl() {
        InMemoryResumableStreamBuffer buffer = buffer(100, 600, 900);
        buffer.open(meta("turn-1", "t1"));
        buffer.append("turn-1", "a");
        buffer.finish("turn-1", "complete");

        now.addAndGet(599_000L);
        assertTrue(buffer.read("t1", "turn-1", 0L).found());
        assertTrue(buffer.meta("t1", "turn-1").isPresent());

        now.addAndGet(2_000L);
        assertFalse(buffer.read("t1", "turn-1", 0L).found());
        assertTrue(buffer.meta("t1", "turn-1").isEmpty());
    }

    @Test
    void aRunningTurnThatStopsAppendingEventuallyExpiresToo() {
        // detach 900s + ttl 600s: a leak guard for a turn whose producer vanished without finishing.
        InMemoryResumableStreamBuffer buffer = buffer(100, 600, 900);
        buffer.open(meta("turn-1", "t1"));
        buffer.append("turn-1", "a");

        now.addAndGet(1_499_000L);
        assertTrue(buffer.read("t1", "turn-1", 0L).found());
        // An append refreshes the running TTL.
        buffer.append("turn-1", "b");
        now.addAndGet(1_499_000L);
        assertTrue(buffer.read("t1", "turn-1", 0L).found());

        now.addAndGet(2_000L);
        assertFalse(buffer.read("t1", "turn-1", 0L).found());
    }

    @Test
    void anotherTenantsTurnIsInvisible() {
        InMemoryResumableStreamBuffer buffer = buffer(100, 600, 900);
        buffer.open(meta("turn-1", "tenant-a"));
        buffer.append("turn-1", "secret");

        assertTrue(buffer.read("tenant-a", "turn-1", 0L).found());
        assertFalse(buffer.read("tenant-b", "turn-1", 0L).found());
        assertTrue(buffer.meta("tenant-b", "turn-1").isEmpty());
        assertTrue(buffer.meta("tenant-a", "turn-1").isPresent());
    }

    @Test
    void unknownTurnReadsAsNotFoundAndAppendIsIgnored() {
        InMemoryResumableStreamBuffer buffer = buffer(100, 600, 900);

        assertFalse(buffer.read("t1", "nope", 0L).found());
        assertEquals(-1L, buffer.append("nope", "a"));
        assertEquals(-1L, buffer.finish("nope", "complete"));
    }

    @Test
    void reopeningAnExistingTurnKeepsItsEntries() {
        InMemoryResumableStreamBuffer buffer = buffer(100, 600, 900);
        buffer.open(meta("turn-1", "t1"));
        buffer.append("turn-1", "a");
        buffer.open(meta("turn-1", "t1"));

        assertEquals(List.of(1L), ids(buffer.read("t1", "turn-1", 0L)));
    }
}
