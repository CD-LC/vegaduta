package com.agenticai.core.agents.agui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.agui.AgUiRunEventBuffer.BufferedEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The durable-buffer half of AG-UI stream rejoin. Same fake-Redis convention
 * as {@code A2aTaskStoreRedisTest}: mocked ops interfaces backed by maps, and
 * TWO {@link AgUiRunEventBuffer} instances sharing one fake standing in for
 * two core replicas - which is what lets the cross-pod rejoin contract be
 * pinned without a live Redis or a second JVM.
 */
class AgUiRunEventBufferTest {

    private final Map<String, List<String>> lists = new HashMap<>();
    private final Map<String, String> values = new HashMap<>();
    private StringRedisTemplate redis;
    private ListOperations<String, String> listOps;
    private AgUiRunEventBuffer podA;
    private AgUiRunEventBuffer podB;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForList()).thenReturn(listOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(listOps.rightPush(anyString(), anyString())).thenAnswer(inv -> {
            List<String> list = lists.computeIfAbsent(inv.getArgument(0), k -> new ArrayList<>());
            list.add(inv.getArgument(1));
            return (long) list.size();
        });
        when(listOps.range(anyString(), org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.eq(-1L)))
            .thenAnswer(inv -> {
                List<String> list = lists.get(inv.getArgument(0));
                return list == null ? null : new ArrayList<>(list);
            });
        org.mockito.Mockito.doAnswer(inv -> {
            values.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(redis.hasKey(anyString())).thenAnswer(inv -> values.containsKey(inv.<String>getArgument(0)));

        // Two pods sharing one Redis - the only difference between them is
        // their pod-local liveRuns map, exactly like the real deployment.
        podA = new AgUiRunEventBuffer(redis);
        podB = new AgUiRunEventBuffer(redis);
    }

    @Test
    void recordAssignsSequenceFromListPosition() {
        podA.open("acme", "run-1");
        assertEquals(1, podA.record("acme", "run-1", "{\"type\":\"RUN_STARTED\"}"));
        assertEquals(2, podA.record("acme", "run-1", "{\"type\":\"TEXT_MESSAGE_START\"}"));
        assertEquals(3, podA.record("acme", "run-1", "{\"type\":\"RUN_FINISHED\"}"));
    }

    @Test
    void readAfterReturnsOnlyEventsPastTheResumePoint() {
        podA.open("acme", "run-1");
        podA.record("acme", "run-1", "e1");
        podA.record("acme", "run-1", "e2");
        podA.record("acme", "run-1", "e3");

        assertEquals(List.of(), podA.readAfter("acme", "run-1", 3));
        List<BufferedEvent> tail = podA.readAfter("acme", "run-1", 1);
        assertEquals(2, tail.size());
        assertEquals(2, tail.get(0).seq());
        assertEquals("e2", tail.get(0).json());
        assertEquals(3, tail.get(1).seq());
        // The full buffer for a client that saw nothing.
        assertEquals(3, podA.readAfter("acme", "run-1", 0).size());
        // An unknown run reads as empty, never an error.
        assertEquals(List.of(), podA.readAfter("acme", "nope", 0));
    }

    @Test
    void liveTailReplaysHistoryToALateRejoin() {
        podA.open("acme", "run-1");
        podA.record("acme", "run-1", "e1");
        podA.record("acme", "run-1", "e2");

        // A rejoin subscribing AFTER both events were recorded still gets them
        // - replay().all() is what makes the buffer-then-attach race unwinnable
        // for the gap.
        List<BufferedEvent> replayed = podA.liveTail("acme", "run-1").asFlux()
            .take(2).collectList().block(Duration.ofSeconds(5));
        assertEquals(2, replayed.size());
        assertEquals(1, replayed.get(0).seq());
        assertEquals(2, replayed.get(1).seq());
    }

    @Test
    void finishMarksDoneAndDropsTheLiveSink() {
        podA.open("acme", "run-1");
        podA.record("acme", "run-1", "e1");
        assertFalse(podA.isFinished("acme", "run-1"));

        podA.finish("acme", "run-1");

        assertTrue(podA.isFinished("acme", "run-1"));
        assertNull(podA.liveTail("acme", "run-1"));
        // The durable buffer survives - a rejoin of a completed run still replays.
        assertEquals(1, podA.readAfter("acme", "run-1", 0).size());
    }

    @Test
    void aRejoinOnAnotherPodSeesTheBufferButNoLiveTail() {
        // Pod A runs the turn; pod B serves the rejoin (Container Apps has no
        // session affinity - this IS the common rejoin topology).
        podA.open("acme", "run-1");
        podA.record("acme", "run-1", "e1");
        podA.record("acme", "run-1", "e2");

        assertNull(podB.liveTail("acme", "run-1"));
        assertEquals(2, podB.readAfter("acme", "run-1", 0).size());
        // And once A finishes, B sees the done marker through the shared store.
        podA.finish("acme", "run-1");
        assertTrue(podB.isFinished("acme", "run-1"));
    }

    @Test
    void bufferKeysAreTenantScoped() {
        podA.open("acme", "run-1");
        podA.open("beta", "run-1");
        podA.record("acme", "run-1", "acme-e1");

        // A client-chosen runId can collide across tenants; the events cannot.
        assertEquals(1, podA.readAfter("acme", "run-1", 0).size());
        assertEquals(List.of(), podA.readAfter("beta", "run-1", 0));
    }

    @Test
    void aRedisFailureDegradesToLiveOnly() {
        // A sick Redis must never take the RUNNING turn down - record() still
        // feeds the live tail, just without a durable seq.
        doThrow(new RedisConnectionFailureException("down")).when(listOps)
            .rightPush(anyString(), anyString());
        podA.open("acme", "run-1");

        long seq = podA.record("acme", "run-1", "e1");

        assertEquals(-1, seq);
        BufferedEvent live = podA.liveTail("acme", "run-1").asFlux()
            .take(1).single().block(Duration.ofSeconds(5));
        assertEquals("e1", live.json());
    }
}
