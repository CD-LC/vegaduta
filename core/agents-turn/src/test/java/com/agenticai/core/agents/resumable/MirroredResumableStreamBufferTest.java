package com.agenticai.core.agents.resumable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.resumable.ResumableStreamBuffer.Entry;
import com.agenticai.core.agents.resumable.ResumableStreamBuffer.ReadResult;
import com.agenticai.core.agents.resumable.ResumableStreamBuffer.TurnMeta;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@code store=redis}: two "replicas" (two {@link MirroredResumableStreamBuffer}s, each with its own memory)
 * share one fake Redis - the same convention as {@code AgUiRunEventBufferTest}. Pins that a replica that did not
 * generate a turn can replay it, that tenants stay isolated by key, that gaps are detected, and that a Redis
 * outage never breaks the generating node.
 */
class MirroredResumableStreamBufferTest {

    private final Map<String, List<String>> lists = new HashMap<>();
    private final Map<String, String> values = new HashMap<>();
    private final AtomicLong now = new AtomicLong(5_000_000L);
    private final AtomicBoolean failNextPush = new AtomicBoolean(false);
    private StringRedisTemplate redis;
    private ListOperations<String, String> listOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForList()).thenReturn(listOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(listOps.rightPush(anyString(), anyString())).thenAnswer(inv -> {
            if (failNextPush.getAndSet(false)) {
                throw new RedisConnectionFailureException("down");
            }
            List<String> list = lists.computeIfAbsent(inv.getArgument(0), k -> new ArrayList<>());
            list.add(inv.getArgument(1));
            return (long) list.size();
        });
        when(listOps.range(anyString(), anyLong(), anyLong())).thenAnswer(inv -> {
            List<String> list = lists.get(inv.<String>getArgument(0));
            if (list == null) {
                return List.of();
            }
            long start = inv.getArgument(1);
            long end = inv.getArgument(2);
            int size = list.size();
            long from = start < 0 ? Math.max(0, size + start) : start;
            long to = end < 0 ? size + end : Math.min(end, size - 1L);
            if (from > to || from >= size) {
                return List.of();
            }
            return new ArrayList<>(list.subList((int) from, (int) to + 1));
        });
        doAnswer(inv -> {
            List<String> list = lists.get(inv.<String>getArgument(0));
            if (list != null) {
                long start = inv.getArgument(1);
                long end = inv.getArgument(2);
                int size = list.size();
                int from = (int) (start < 0 ? Math.max(0, size + start) : start);
                int to = (int) (end < 0 ? size + end : Math.min(end, size - 1L));
                lists.put(inv.getArgument(0), new ArrayList<>(list.subList(from, to + 1)));
            }
            return null;
        }).when(listOps).trim(anyString(), anyLong(), anyLong());
        doAnswer(inv -> {
            values.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(valueOps.get(anyString())).thenAnswer(inv -> values.get(inv.<String>getArgument(0)));
        when(redis.hasKey(anyString())).thenAnswer(inv ->
            values.containsKey(inv.<String>getArgument(0)) || lists.containsKey(inv.<String>getArgument(0)));
    }

    private MirroredResumableStreamBuffer replica(int maxEvents) {
        InMemoryResumableStreamBuffer memory = new InMemoryResumableStreamBuffer(maxEvents, 600, 900, now::get);
        return new MirroredResumableStreamBuffer(memory, new RedisResumableStreamBuffer(redis, maxEvents, 600, 900));
    }

    private static TurnMeta meta(String turnId, String tenant) {
        return new TurnMeta(turnId, tenant, "alice", UUID.randomUUID().toString(), "s1", 1L);
    }

    private static List<String> payloads(ReadResult result) {
        return result.entries().stream().map(Entry::payload).toList();
    }

    @Test
    void anotherReplicaReplaysATurnItDidNotGenerate() {
        MirroredResumableStreamBuffer podA = replica(100);
        MirroredResumableStreamBuffer podB = replica(100);
        podA.open(meta("turn-1", "tenant-a"));
        podA.append("turn-1", "a");
        podA.append("turn-1", "b");
        podA.append("turn-1", "c");
        podA.finish("turn-1", "complete");

        ReadResult all = podB.read("tenant-a", "turn-1", 0L);
        assertTrue(all.found());
        assertFalse(all.gap());
        assertEquals(List.of("a", "b", "c", "complete"), payloads(all));
        assertTrue(all.entries().get(3).terminal());
        // after-id semantics survive the Redis round trip.
        ReadResult tail = podB.read("tenant-a", "turn-1", 2L);
        assertEquals(List.of("c", "complete"), payloads(tail));
        assertEquals(3L, tail.entries().get(0).id());
        assertTrue(podB.meta("tenant-a", "turn-1").isPresent());
        assertEquals("alice", podB.meta("tenant-a", "turn-1").get().ownerSubject());
    }

    @Test
    void anotherTenantCannotSeeTheTurnOnEitherReplica() {
        MirroredResumableStreamBuffer podA = replica(100);
        MirroredResumableStreamBuffer podB = replica(100);
        podA.open(meta("turn-1", "tenant-a"));
        podA.append("turn-1", "secret");

        assertFalse(podA.read("tenant-b", "turn-1", 0L).found());
        assertFalse(podB.read("tenant-b", "turn-1", 0L).found());
        assertTrue(podB.meta("tenant-b", "turn-1").isEmpty());
    }

    @Test
    void payloadsContainingTheSeparatorAndNewlinesRoundTrip() {
        MirroredResumableStreamBuffer podA = replica(100);
        MirroredResumableStreamBuffer podB = replica(100);
        podA.open(meta("turn-1", "t"));
        podA.append("turn-1", "x|D|y\nline two | pipe");

        assertEquals(List.of("x|D|y\nline two | pipe"), payloads(podB.read("t", "turn-1", 0L)));
    }

    @Test
    void trimmedRedisListIsReportedAsAGapNotAnIncompleteReplay() {
        MirroredResumableStreamBuffer podA = replica(20);
        MirroredResumableStreamBuffer podB = replica(20);
        podA.open(meta("turn-1", "t"));
        for (int i = 1; i <= 200; i++) {
            podA.append("turn-1", "c" + i);
        }

        // Cursor at 0 needs id 1, long gone from Redis.
        assertTrue(podB.read("t", "turn-1", 0L).gap());
        // A cursor close to the head is still servable.
        ReadResult recent = podB.read("t", "turn-1", 195L);
        assertFalse(recent.gap());
        assertEquals(List.of("c196", "c197", "c198", "c199", "c200"), payloads(recent));
    }

    @Test
    void aLostMirrorWriteSurfacesAsAGapOnTheOtherReplica() {
        MirroredResumableStreamBuffer podA = replica(100);
        MirroredResumableStreamBuffer podB = replica(100);
        podA.open(meta("turn-1", "t"));
        podA.append("turn-1", "a");
        // Redis hiccup swallows exactly one mirrored entry.
        failNextPush.set(true);
        podA.append("turn-1", "b");
        podA.append("turn-1", "c");

        // The generating node is unaffected...
        assertEquals(List.of("a", "b", "c"), payloads(podA.read("t", "turn-1", 0L)));
        // ...and the other replica refuses to serve a replay with a hole in it.
        assertTrue(podB.read("t", "turn-1", 0L).gap());
    }

    @Test
    void aRedisOutageNeverBreaksTheGeneratingNodeOrItsReaders() {
        MirroredResumableStreamBuffer pod = replica(100);
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        when(redis.opsForList()).thenThrow(new RedisConnectionFailureException("down"));

        pod.open(meta("turn-1", "t"));
        assertEquals(1L, pod.append("turn-1", "a"));
        assertEquals(2L, pod.finish("turn-1", "complete"));

        ReadResult local = pod.read("t", "turn-1", 0L);
        assertTrue(local.found());
        assertEquals(List.of("a", "complete"), payloads(local));
        // A turn only Redis could know about reads as not found while Redis is down - not as an exception.
        assertFalse(pod.read("t", "unknown-turn", 0L).found());
        assertTrue(pod.meta("t", "unknown-turn").isEmpty());
    }

    @Test
    void entryEncodingRoundTripsAndRejectsGarbage() {
        Entry data = new Entry(42L, "hello | world", false);
        Entry decoded = RedisResumableStreamBuffer.decode(RedisResumableStreamBuffer.encode(data));
        assertNotNull(decoded);
        assertEquals(data, decoded);

        Entry terminal = new Entry(7L, "detach-timeout", true);
        assertEquals(terminal, RedisResumableStreamBuffer.decode(RedisResumableStreamBuffer.encode(terminal)));

        assertNull(RedisResumableStreamBuffer.decode(null));
        assertNull(RedisResumableStreamBuffer.decode("no separators"));
        assertNull(RedisResumableStreamBuffer.decode("x|D|payload"));
        assertNull(RedisResumableStreamBuffer.decode("5|Q|payload"));
        assertNull(RedisResumableStreamBuffer.decode("5|DD|payload"));
    }

    @Test
    void keysAreTenantFirst() {
        assertEquals("agentic:chat-resume:tenant-a:turn-1:ev", RedisResumableStreamBuffer.listKey("tenant-a", "turn-1"));
        assertEquals("agentic:chat-resume:tenant-a:turn-1:meta", RedisResumableStreamBuffer.metaKey("tenant-a", "turn-1"));
    }
}
