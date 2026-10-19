package com.agenticai.core.omnichannel.whatsappvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TimeBoundedRunnerTest {

    private TimeBoundedRunner runner;

    @AfterEach
    void close() {
        TenantContext.clear();
        if (runner != null) {
            runner.close();
        }
    }

    @Test
    void returnsTheResultWithinTheDeadline() throws Exception {
        runner = new TimeBoundedRunner(1, 1);

        assertEquals("ok", runner.call(Duration.ofSeconds(2), () -> "ok"));
    }

    @Test
    void givesUpAfterTheDeadline() {
        runner = new TimeBoundedRunner(1, 1);
        CountDownLatch release = new CountDownLatch(1);

        long started = System.nanoTime();
        assertThrows(TimeoutException.class, () -> runner.call(Duration.ofMillis(200), () -> {
            release.await(5, TimeUnit.SECONDS);
            return "late";
        }));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        release.countDown();
        assertTrue(elapsedMs < 2_000, "returned after " + elapsedMs + " ms");
    }

    @Test
    void unwrapsTheCallersOwnException() {
        runner = new TimeBoundedRunner(1, 1);

        VoiceNoteException thrown = assertThrows(VoiceNoteException.class, () -> runner.call(Duration.ofSeconds(2), () -> {
            throw new VoiceNoteException(VoiceNoteOutcome.STT_FAILED, "x");
        }));

        assertEquals(VoiceNoteOutcome.STT_FAILED, thrown.getOutcome());
    }

    @Test
    void rejectsImmediatelyWhenThePoolAndQueueAreFull() throws Exception {
        runner = new TimeBoundedRunner(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        // Occupy the single worker, then the single queue slot (both from helper threads, since call() blocks).
        Thread first = new Thread(() -> blockOn(running, release));
        first.setDaemon(true);
        first.start();
        assertTrue(running.await(2, TimeUnit.SECONDS));
        Thread second = new Thread(() -> blockOn(null, release));
        second.setDaemon(true);
        second.start();
        Thread.sleep(300);

        assertThrows(RejectedExecutionException.class, () -> runner.call(Duration.ofSeconds(1), () -> "never"));

        release.countDown();
    }

    private void blockOn(CountDownLatch running, CountDownLatch release) {
        try {
            runner.call(Duration.ofSeconds(10), () -> {
                if (running != null) {
                    running.countDown();
                }
                release.await(10, TimeUnit.SECONDS);
                return "done";
            });
        } catch (Exception ignored) {
            // The test only needs these to occupy the pool.
        }
    }

    @Test
    void carriesTheCallersTenantContextToTheWorkerAndClearsItAfter() throws Exception {
        runner = new TimeBoundedRunner(1, 1);
        TenantContext.set(TenantContext.of("tenant_x", "subject", Set.of()));
        AtomicReference<String> seen = new AtomicReference<>();

        runner.call(Duration.ofSeconds(2), () -> {
            seen.set(TenantContext.current().map(TenantContext::getTenantId).orElse("none"));
            return null;
        });
        AtomicReference<String> afterwards = new AtomicReference<>();
        TenantContext.clear();
        runner.call(Duration.ofSeconds(2), () -> {
            afterwards.set(TenantContext.current().map(TenantContext::getTenantId).orElse("none"));
            return null;
        });

        assertEquals("tenant_x", seen.get());
        assertEquals("none", afterwards.get());
        assertFalse(TenantContext.current().isPresent());
    }
}
