package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link StreamEndExecutor}: off means a plain method call; on means bounded, never dropping,
 * drained on stop and clear of tenant state; and {@link LlmCallLogService} writes its row on the
 * pool thread under the caller's tenant.
 */
class StreamEndExecutorTest {

    private StreamEndExecutor executor;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (executor != null) {
            executor.stop();
        }
    }

    private static StreamEndExecutor async(int threads, int queue, Duration drain) {
        StreamEndExecutor e = new StreamEndExecutor(true, threads, queue, drain);
        e.start();
        return e;
    }

    @Test
    void offRunsOnTheCallingThreadAndIsAPlainCall() {
        executor = new StreamEndExecutor(false, 4, 100, Duration.ofSeconds(1));
        AtomicReference<Thread> ran = new AtomicReference<>();

        executor.run(() -> ran.set(Thread.currentThread()));

        assertEquals(Thread.currentThread(), ran.get());
        assertFalse(executor.isAsync());
        assertFalse(StreamEndExecutor.INLINE.isAsync());
    }

    @Test
    void onRunsOffTheCallingThread() throws Exception {
        executor = async(2, 10, Duration.ofSeconds(5));
        AtomicReference<Thread> ran = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.run(
                () -> {
                    ran.set(Thread.currentThread());
                    done.countDown();
                });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNotEquals(Thread.currentThread(), ran.get());
    }

    @Test
    void aFullQueueRunsTheWorkOnTheCallerInsteadOfDroppingIt() throws Exception {
        executor = async(1, 1, Duration.ofSeconds(5));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch busy = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        AtomicReference<Thread> overflowThread = new AtomicReference<>();

        executor.run(
                () -> { // occupies the only worker
                    busy.countDown();
                    await(release);
                    ran.incrementAndGet();
                });
        assertTrue(busy.await(5, TimeUnit.SECONDS));
        executor.run(ran::incrementAndGet); // fills the one queue slot
        executor.run(
                () -> { // nowhere to go: backpressure, runs here
                    overflowThread.set(Thread.currentThread());
                    ran.incrementAndGet();
                });

        assertEquals(Thread.currentThread(), overflowThread.get());
        release.countDown();
        executor.stop();
        assertEquals(3, ran.get(), "all three ran, none dropped");
    }

    @Test
    void anOverflowRunInlineLeavesTheCallersTenantContextIntact() throws Exception {
        executor = async(1, 1, Duration.ofSeconds(5));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch busy = new CountDownLatch(1);
        executor.run(
                () -> {
                    busy.countDown();
                    await(release);
                });
        assertTrue(busy.await(5, TimeUnit.SECONDS));
        executor.run(() -> {}); // fills the one queue slot

        TenantContext caller = TenantContext.of("caller-tenant", "alice", Set.of("USER"));
        TenantContext.set(caller);
        executor.run(() -> {}); // overflow: runs on this thread

        assertEquals(
                caller,
                TenantContext.current().orElse(null),
                "the request thread must keep its tenant after an inline overflow run");
        release.countDown();
    }

    @Test
    void workRunInlineAfterStopLeavesTheCallersTenantContextIntact() {
        executor = async(1, 10, Duration.ofSeconds(5));
        executor.stop();

        TenantContext caller = TenantContext.of("caller-tenant", "alice", Set.of("USER"));
        TenantContext.set(caller);
        executor.run(() -> {});

        assertEquals(caller, TenantContext.current().orElse(null));
    }

    @Test
    void stopDrainsQueuedWorkAndLaterWorkRunsInline() {
        executor = async(2, 100, Duration.ofSeconds(10));
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 50; i++) {
            executor.run(
                    () -> {
                        sleep(5);
                        ran.incrementAndGet();
                    });
        }

        executor.stop();

        assertEquals(50, ran.get(), "everything queued before the stop was run by the drain");
        AtomicReference<Thread> after = new AtomicReference<>();
        executor.run(() -> after.set(Thread.currentThread()));
        assertEquals(
                Thread.currentThread(),
                after.get(),
                "after the stop, work runs inline instead of being lost");
    }

    @Test
    void aStuckTaskDoesNotHangTheStopPastTheDrainTimeout() {
        executor = async(1, 10, Duration.ofMillis(200));
        executor.run(() -> sleep(5000));
        long start = System.nanoTime();

        executor.stop();

        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);
    }

    @Test
    void aFailingTaskNeitherKillsThePoolNorLeaksTenantState() throws Exception {
        executor = async(1, 10, Duration.ofSeconds(5));
        AtomicReference<TenantContext> seenByNext =
                new AtomicReference<>(TenantContext.of("leak", "x", Set.of()));
        CountDownLatch done = new CountDownLatch(1);

        executor.run(
                () -> {
                    TenantContext.set(TenantContext.of("t1", "u1", Set.of("USER")));
                    throw new IllegalStateException("boom");
                });
        executor.run(
                () -> {
                    seenByNext.set(TenantContext.current().orElse(null));
                    done.countDown();
                });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNull(
                seenByNext.get(),
                "the pool thread's tenant context was cleared after the failing task");
    }

    @Test
    void aTaskThatSetsTheAgentVersionPinDoesNotLeakItToTheNextTask() throws Exception {
        executor = async(1, 10, Duration.ofSeconds(5));
        AtomicReference<java.util.UUID> seenByNext =
                new AtomicReference<>(java.util.UUID.randomUUID());
        CountDownLatch done = new CountDownLatch(1);

        executor.run(
                () -> com.agenticai.core.security.AgentVersionPin.set(java.util.UUID.randomUUID()));
        executor.run(
                () -> {
                    seenByNext.set(com.agenticai.core.security.AgentVersionPin.current());
                    done.countDown();
                });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNull(seenByNext.get(), "the pool thread's agent-version pin was cleared");
    }

    @Test
    void theCallLogRowIsWrittenOnAPoolThreadUnderTheCallersTenant() throws Exception {
        executor = async(1, 10, Duration.ofSeconds(5));
        LlmCallLogRepository repository = mock(LlmCallLogRepository.class);
        AtomicReference<Thread> savedOn = new AtomicReference<>();
        AtomicReference<String> tenantAtSave = new AtomicReference<>();
        CountDownLatch saved = new CountDownLatch(1);
        org.mockito.Mockito.when(repository.save(any(LlmCallLog.class)))
                .thenAnswer(
                        inv -> {
                            savedOn.set(Thread.currentThread());
                            tenantAtSave.set(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse(null));
                            saved.countDown();
                            return inv.getArgument(0);
                        });
        LlmCallLogService service =
                new LlmCallLogService(
                        repository,
                        new LlmPricingCatalog(new SimpleMeterRegistry(), System::nanoTime));
        service.setStreamEndExecutor(executor);
        TenantContext.set(TenantContext.of("tenant-7", "alice", Set.of("USER")));

        service.record(
                null,
                "s1",
                ProviderName.GEMINI,
                "gemini-2.5-flash",
                10L,
                5L,
                15L,
                true,
                null,
                "r1",
                null,
                null,
                3L,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertTrue(saved.await(5, TimeUnit.SECONDS));
        assertNotEquals(Thread.currentThread(), savedOn.get());
        assertEquals("tenant-7", tenantAtSave.get());
        verify(repository).save(any(LlmCallLog.class));
    }

    @Test
    void withTheExecutorOffTheCallLogRowIsWrittenInlineAsBefore() {
        LlmCallLogRepository repository = mock(LlmCallLogRepository.class);
        LlmCallLogService service =
                new LlmCallLogService(
                        repository,
                        new LlmPricingCatalog(new SimpleMeterRegistry(), System::nanoTime));

        service.record(
                null,
                "s1",
                ProviderName.GEMINI,
                "gemini-2.5-flash",
                10L,
                5L,
                15L,
                true,
                null,
                "r1",
                null,
                null,
                3L,
                Instant.now(),
                null,
                null,
                null,
                null,
                null);

        verify(repository).save(any(LlmCallLog.class));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
