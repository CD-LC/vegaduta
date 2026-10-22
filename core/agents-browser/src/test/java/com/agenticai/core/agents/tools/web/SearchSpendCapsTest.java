package com.agenticai.core.agents.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The paid-spend caps: atomic search reservation (no check-then-act overshoot), the in-process
 * fallback when Redis is away, refund of unused searches, and the wall-clock deadline on a page
 * body read.
 */
class SearchSpendCapsTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> noRedis() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> brokenRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(values.increment(anyString(), anyLong()))
                .thenThrow(new IllegalStateException("redis down"));
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        return provider;
    }

    // ------------------------------------------------------- atomic reserve

    @Test
    void concurrentCallsCanNeverBeGrantedMoreThanTheDailyCap() throws Exception {
        DeepResearchDailyAllowance allowance = new DeepResearchDailyAllowance(noRedis(), 10);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> grants = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                grants.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return allowance.reserve("t1", "alice", 3);
                                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> grant : grants) {
                total += grant.get();
            }
            assertEquals(
                    10, total, "40 callers wanting 3 each must share exactly the 10 searches left");
            assertEquals(0, allowance.remaining("t1", "alice"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aPartialGrantTakesWhatIsLeftAndRefundGivesUnusedSearchesBack() {
        DeepResearchDailyAllowance allowance = new DeepResearchDailyAllowance(noRedis(), 5);
        assertEquals(3, allowance.reserve("t1", "alice", 3));
        assertEquals(2, allowance.reserve("t1", "alice", 4), "only 2 were left");
        assertEquals(0, allowance.reserve("t1", "alice", 1));
        assertEquals(0, allowance.remaining("t1", "alice"));
        allowance.refund("t1", "alice", 2);
        assertEquals(2, allowance.remaining("t1", "alice"));
        // Another user has their own window.
        assertEquals(5, allowance.remaining("t1", "bob"));
    }

    @Test
    void uncappedAndZeroRequestsNeverTouchTheCounter() {
        DeepResearchDailyAllowance off = new DeepResearchDailyAllowance(noRedis(), 0);
        assertEquals(7, off.reserve("t1", "alice", 7));
        assertEquals(0, off.reserve("t1", "alice", 0));
        off.refund("t1", "alice", 3);
        assertEquals(Integer.MAX_VALUE, off.remaining("t1", "alice"));
    }

    @Test
    void aRedisOutageDegradesToAPerReplicaCapNotNoCap() {
        DeepResearchDailyAllowance allowance = new DeepResearchDailyAllowance(brokenRedis(), 4);
        assertEquals(4, allowance.reserve("t1", "alice", 4));
        assertEquals(0, allowance.reserve("t1", "alice", 1));
        assertEquals(0, allowance.remaining("t1", "alice"));
    }

    @Test
    void theToolReservesBeforeSearchingAndRefusesWhenNothingIsGranted() throws Exception {
        DeepResearchDailyAllowance allowance = new DeepResearchDailyAllowance(noRedis(), 3);
        AtomicInteger searches = new AtomicInteger();
        DeepResearchService service =
                new DeepResearchService(
                        DeepResearchConfig.defaults(),
                        (query, count) -> {
                            searches.incrementAndGet();
                            return List.of(
                                    new BraveWebSearchClient.SearchResult(
                                            "A", "https://example.com/a", "d"));
                        },
                        (url, maxChars) ->
                                "Solid-state batteries reach 400 Wh/kg in lab cells. Another sentence follows here.");
        DeepResearchToolCallback tool =
                new DeepResearchToolCallback(
                        service,
                        new DeepResearchSession(12, 12),
                        allowance.forCaller("t1", "alice", null));
        String call =
                "{\"question\": \"battery progress\", \"effort\": \"comparison\", "
                        + "\"subQuestions\": [\"solid state\", \"lithium\", \"sodium\", \"zinc\"]}";

        JsonNode first = MAPPER.readTree(tool.call(call));
        assertEquals(
                3, first.get("workers").asInt(), "four angles wanted, three searches left today");
        assertEquals(3, searches.get());
        assertEquals(
                0, allowance.remaining("t1", "alice"), "charged up front for exactly what ran");

        JsonNode second = MAPPER.readTree(tool.call(call));
        assertTrue(
                second.get("error").asString().contains("allowance for this account is used up"));
        assertEquals(3, searches.get(), "a refused call runs no searches");
    }

    // ------------------------------------------------- body read deadline

    /** A server that sends one byte every 50 ms, forever. */
    private static final class SlowDrip extends InputStream {
        @Override
        public int read() throws IOException {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            return 'a';
        }

        /**
         * Like a socket: hand back what is available (one byte) rather than blocking to fill the
         * buffer.
         */
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int c = read();
            b[off] = (byte) c;
            return 1;
        }
    }

    /** A server that went silent: read blocks until the stream is closed. */
    private static final class Stalled extends InputStream {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    @Test
    void aSlowDripBodyIsCutOffAtTheWallClockDeadline() {
        long began = System.nanoTime();
        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () -> WebPageFetcher.readCapped(new SlowDrip(), Duration.ofMillis(300)));
        assertTrue(e.getMessage().contains("too long"));
        assertTrue(Duration.ofNanos(System.nanoTime() - began).toSeconds() < 5);
    }

    @Test
    void aSilentStalledReadIsReleasedByTheWatchdog() {
        long began = System.nanoTime();
        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () -> WebPageFetcher.readCapped(new Stalled(), Duration.ofMillis(300)));
        assertTrue(e.getMessage().contains("too long"));
        assertTrue(Duration.ofNanos(System.nanoTime() - began).toSeconds() < 5);
    }

    @Test
    void aBodyThatFinishesInTimeIsReadInFull() {
        byte[] body = "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] read =
                WebPageFetcher.readCapped(
                        new java.io.ByteArrayInputStream(body), Duration.ofSeconds(5));
        assertEquals("hello world", new String(read, java.nio.charset.StandardCharsets.UTF_8));
    }
}
