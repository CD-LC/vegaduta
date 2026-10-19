package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.Disposable;

/**
 * {@link ChatTurnCancelService} - mocked Redis. Pins the contracts the stream integration relies
 * on: the flag write carries its TTL; Redis blips fail OPEN on both the write and the poll (a turn
 * must never die because the control channel hiccupped); the watcher emits exactly once and the
 * flag is consumed on observation (so a cancel on turn N cannot bleed into turn N+1); and the cost
 * of watching is one Redis round trip per poll for the whole pod, not one per in-flight turn.
 *
 * <p>No reactor-test on this module's classpath - the watcher assertions use {@code blockFirst}
 * with a timeout instead, and a short poll interval so the suite stays fast.
 */
class ChatTurnCancelServiceTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String SESSION = "session-1";
    private static final String KEY = "agentic:chat-turn-cancel:" + AGENT + ":" + SESSION;
    private static final Duration FAST = Duration.ofMillis(50);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ChatTurnCancelService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = new ChatTurnCancelService(redis, FAST);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @SuppressWarnings("unchecked")
    private void pipelineReturns(Object... replies) {
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(List.of(replies));
    }

    @Test
    void theDefaultPollIntervalIsTheSameHalfSecondAsBefore() {
        assertEquals(Duration.ofMillis(500), ChatTurnCancelService.DEFAULT_POLL_INTERVAL);
    }

    @Test
    void requestCancellationWritesTheFlagWithItsTtl() {
        assertTrue(service.requestCancellation(AGENT, SESSION));
        verify(values).set(eq(KEY), eq("1"), eq(Duration.ofMinutes(2)));
    }

    @Test
    void requestCancellationFailsOpenWhenRedisIsDown() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));

        assertFalse(service.requestCancellation(AGENT, SESSION));
    }

    @Test
    void watcherEmitsOnceWhenTheFlagAppears() {
        pipelineReturns(1L);

        assertEquals(
                0L, service.watchForCancellation(AGENT, SESSION).blockFirst(Duration.ofSeconds(3)));
    }

    @Test
    void watcherStaysSilentWhileNoFlagIsSet() {
        pipelineReturns(0L);

        assertThrows(
                IllegalStateException.class,
                () ->
                        service.watchForCancellation(AGENT, SESSION)
                                .blockFirst(Duration.ofMillis(400)));
    }

    @Test
    void watcherFailsOpenOnRedisBlips() {
        when(redis.execute(any(RedisScript.class), anyList()))
                .thenThrow(new RedisConnectionFailureException("blip"));

        assertThrows(
                IllegalStateException.class,
                () ->
                        service.watchForCancellation(AGENT, SESSION)
                                .blockFirst(Duration.ofMillis(400)));
    }

    @Test
    void aConsumedFlagCancelsExactlyOneWaitingTurn() {
        // Two turns racing on one session share a flag key; one write is one cancel.
        List<Object> signals = new java.util.concurrent.CopyOnWriteArrayList<>();
        Disposable first = service.watchForCancellation(AGENT, SESSION).subscribe(signals::add);
        Disposable second = service.watchForCancellation(AGENT, SESSION).subscribe(signals::add);
        pipelineReturns(1L);

        service.pollOnce();
        awaitSignals(signals, 1);
        pipelineReturns(0L);
        service.pollOnce();

        assertEquals(1, signals.size());
        first.dispose();
        second.dispose();
    }

    @Test
    void nothingIsPolledWhileNoTurnIsWatching() throws Exception {
        Thread.sleep(200);
        service.pollOnce();

        verify(redis, never()).execute(any(RedisScript.class), anyList());
    }

    @Test
    void aFinishedTurnStopsBeingChecked() throws Exception {
        pipelineReturns(0L);
        Disposable turn = service.watchForCancellation(AGENT, SESSION).subscribe();
        service.pollOnce();
        verify(redis, times(1)).execute(any(RedisScript.class), anyList());

        turn.dispose();
        service.pollOnce();

        verify(redis, times(1)).execute(any(RedisScript.class), anyList());
    }

    @Test
    void manyTurnsCostOneRedisRoundTripPerPollNotOnePerTurn() throws Exception {
        int turns = 200;
        List<Disposable> subs = new ArrayList<>();
        when(redis.execute(any(RedisScript.class), anyList()))
                .thenAnswer(
                        inv -> {
                            List<Object> zeros = new ArrayList<>();
                            for (int i = 0; i < turns; i++) {
                                zeros.add(0L);
                            }
                            return zeros;
                        });
        for (int i = 0; i < turns; i++) {
            subs.add(service.watchForCancellation(AGENT, "s-" + i).subscribe());
        }

        Thread.sleep(525); // about ten 50 ms ticks
        subs.forEach(Disposable::dispose);

        // Before: one Redis DEL per turn per tick (200 x ~10 = ~2000). Now: one script call per
        // tick.
        verify(redis, atMost(14)).execute(any(RedisScript.class), anyList());
        verify(redis, never()).delete(anyString());
    }

    @Test
    void aCancelIsObservedWithinOnePollIntervalPlusTheCheck() throws Exception {
        // The worst case the docs promise: flag written just after a poll -> seen on the next one.
        AtomicBoolean flagged = new AtomicBoolean();
        AtomicLong flaggedAt = new AtomicLong();
        when(redis.execute(any(RedisScript.class), anyList()))
                .thenAnswer(inv -> List.of(flagged.getAndSet(false) ? 1L : 0L));
        long worstMillis = 0;
        for (int round = 0; round < 5; round++) {
            List<Long> seenAt = new java.util.concurrent.CopyOnWriteArrayList<>();
            Disposable turn =
                    service.watchForCancellation(AGENT, SESSION)
                            .subscribe(x -> seenAt.add(System.nanoTime()));
            Thread.sleep(25 + round * 15); // land at a different phase of the 50 ms tick each round
            flaggedAt.set(System.nanoTime());
            flagged.set(true);
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (seenAt.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertFalse(seenAt.isEmpty(), "cancel was never observed");
            worstMillis = Math.max(worstMillis, (seenAt.get(0) - flaggedAt.get()) / 1_000_000);
            turn.dispose();
        }
        // interval (50) + a mocked check (~0) + hand-off to the elastic scheduler; the bound is
        // 2x the interval + 100 ms of CI slack, so it catches a poll that skips a tick but not
        // sub-100 ms jitter.
        assertTrue(
                worstMillis <= 2 * FAST.toMillis() + 100,
                "worst observed cancel latency was " + worstMillis + " ms");
    }

    @Test
    void aRefusedScriptFallsBackToOneDeletePerKeyAndIsRemembered() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList()))
                .thenThrow(new RedisSystemException("NOPERM no permissions to run eval", null));
        String keyA = KEY + "-a";
        String keyB = KEY + "-b";
        when(redis.delete(keyA)).thenReturn(true);
        when(redis.delete(keyB)).thenReturn(false);
        List<Object> signals = new java.util.concurrent.CopyOnWriteArrayList<>();
        Disposable a = service.watchForCancellation(AGENT, SESSION + "-a").subscribe(signals::add);
        Disposable b = service.watchForCancellation(AGENT, SESSION + "-b").subscribe(signals::add);

        service.pollOnce();
        awaitSignals(signals, 1);
        service.pollOnce();

        assertEquals(1, signals.size(), "only the flagged turn is cancelled");
        verify(redis, times(1)).execute(any(RedisScript.class), anyList());
        verify(redis, times(1)).delete(keyA);
        verify(redis, times(2)).delete(keyB);
        a.dispose();
        b.dispose();
    }

    @Test
    void aTimeoutIsNotTreatedAsARefusalAndDoesNotFallBack() {
        when(redis.execute(any(RedisScript.class), anyList()))
                .thenThrow(new QueryTimeoutException("command timed out"));
        Disposable turn = service.watchForCancellation(AGENT, SESSION).subscribe();

        service.pollOnce(); // fail-open: no exception, no cancel

        verify(redis, never()).delete(anyString());
        turn.dispose();
    }

    private static void awaitSignals(List<Object> signals, int n) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (signals.size() < n && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
