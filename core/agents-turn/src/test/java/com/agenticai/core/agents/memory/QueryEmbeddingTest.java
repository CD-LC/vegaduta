package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class QueryEmbeddingTest {

    @Test
    void theEmbedderRunsOnceHoweverOftenTheVectorIsRead() {
        AtomicInteger calls = new AtomicInteger();
        QueryEmbedding e =
                new QueryEmbedding(
                        "hello",
                        text -> {
                            calls.incrementAndGet();
                            return new float[] {1f, 2f};
                        });

        float[] first = e.vector();
        float[] second = e.vector();

        assertSame(first, second);
        assertArrayEquals(new float[] {1f, 2f}, first);
        assertEquals(1, calls.get());
        assertEquals("hello", e.text());
    }

    @Test
    void nothingIsEmbeddedUntilSomeoneAsks() {
        AtomicInteger calls = new AtomicInteger();
        new QueryEmbedding("x", t -> new float[calls.incrementAndGet()]);
        assertEquals(0, calls.get());
    }

    @Test
    void aFailureIsRethrownAndNotRemembered() {
        AtomicInteger calls = new AtomicInteger();
        QueryEmbedding e =
                new QueryEmbedding(
                        "x",
                        t -> {
                            if (calls.incrementAndGet() == 1) {
                                throw new IllegalStateException("down");
                            }
                            return new float[] {7f};
                        });

        assertThrows(IllegalStateException.class, e::vector);
        assertArrayEquals(new float[] {7f}, e.vector());
        assertEquals(2, calls.get());
    }

    /**
     * The embedder is a blocking network call on a virtual thread. Under a monitor it would pin the
     * carrier thread; a few concurrent turns then pin them all and the connection pool drains. It
     * must run without any monitor on this object held.
     */
    @Test
    void theEmbedderNeverRunsUnderAMonitor() {
        AtomicReference<QueryEmbedding> self = new AtomicReference<>();
        AtomicBoolean heldMonitor = new AtomicBoolean(true);
        QueryEmbedding e =
                new QueryEmbedding(
                        "x",
                        t -> {
                            heldMonitor.set(Thread.holdsLock(self.get()));
                            return new float[] {1f};
                        });
        self.set(e);

        e.vector();

        assertFalse(heldMonitor.get(), "the embedder ran inside synchronized(embedding)");
    }

    @Test
    void concurrentCallersShareOneEmbeddingAndNoneIsPinned() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        QueryEmbedding e =
                new QueryEmbedding(
                        "x",
                        t -> {
                            calls.incrementAndGet();
                            try {
                                release.await();
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            return new float[] {3f};
                        });
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<float[]>> results = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                results.add(pool.submit(e::vector));
            }
            release.countDown();
            for (Future<float[]> f : results) {
                assertArrayEquals(new float[] {3f}, f.get());
            }
        }
        assertEquals(1, calls.get());
    }
}
