package com.agenticai.core.omnichannel.session;

import static com.agenticai.core.omnichannel.session.InboundExecutorTestSupport.asTenant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.omnichannel.session.InboundExecutorTestSupport.Manual;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link InboundChannelExecutor} basics: submit, caps, tenant propagation, shutdown, and Spring
 * property binding. Permits, the wait queue, overflow and fairness are in {@link
 * InboundChannelExecutorAdmissionTest}.
 */
class InboundChannelExecutorTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void propagatesTenantContextToWorkerThread() throws Exception {
        InboundChannelExecutor ex =
                new InboundChannelExecutor(Executors.newVirtualThreadPerTaskExecutor(), 4, 4, null);
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        asTenant("tenant_a");

        assertTrue(
                ex.submit(
                        "test",
                        () -> {
                            seen.set(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse("none"));
                            done.countDown();
                        }));

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("tenant_a", seen.get());
    }

    @Test
    void perTenantCapRejectsOneTenantButNotAnother() {
        Manual manual = new Manual();
        InboundChannelExecutor ex = new InboundChannelExecutor(manual, 10, 2, null);

        asTenant("a");
        assertTrue(ex.submit("t", () -> {}));
        assertTrue(ex.submit("t", () -> {}));
        assertFalse(ex.submit("t", () -> {}));
        asTenant("b");
        assertTrue(ex.submit("t", () -> {}));

        assertEquals(3, ex.inFlight());
        manual.runAll();
        assertEquals(0, ex.inFlight());
        assertEquals(0, ex.trackedTenants());
        asTenant("a");
        assertTrue(ex.submit("t", () -> {}));
    }

    @Test
    void globalCapRejectsAndReleasesSlotsWhenTasksFinishEvenOnFailure() {
        Manual manual = new Manual();
        InboundChannelExecutor ex = new InboundChannelExecutor(manual, 2, 2, null);
        asTenant("a");
        assertTrue(
                ex.submit(
                        "t",
                        () -> {
                            throw new IllegalStateException("boom");
                        }));
        asTenant("b");
        assertTrue(ex.submit("t", () -> {}));
        asTenant("c");
        assertFalse(ex.submit("t", () -> {}));

        manual.runAll();

        assertEquals(0, ex.inFlight());
        assertEquals(0, ex.trackedTenants());
        assertTrue(ex.submit("t", () -> {}));
    }

    @Test
    void delegateRejectionReturnsFalseAndHoldsNothing() {
        Executor rejecting =
                r -> {
                    throw new RejectedExecutionException("full");
                };
        InboundChannelExecutor ex = new InboundChannelExecutor(rejecting, 2, 2, null);
        asTenant("a");

        assertFalse(ex.submit("t", () -> {}));

        assertEquals(0, ex.inFlight());
        assertEquals(0, ex.trackedTenants());
    }

    @Test
    void synchronousModeRestoresCallersContext() {
        InboundChannelExecutor ex = InboundChannelExecutor.synchronous();
        asTenant("a");

        ex.submit("t", () -> {});

        assertEquals("a", TenantContext.current().map(TenantContext::getTenantId).orElse("none"));
    }

    @Test
    void realExecutorNeverExceedsCapsUnderContention() throws Exception {
        int global = 6;
        int perTenant = 3;
        java.util.concurrent.ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        InboundChannelExecutor ex = new InboundChannelExecutor(pool, global, perTenant, null);
        java.util.concurrent.atomic.AtomicInteger running =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxRunning =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger accepted =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger completed =
                new java.util.concurrent.atomic.AtomicInteger();
        int submitters = 32;
        int perSubmitter = 25;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < submitters; i++) {
            String tenant = "t" + (i % 4);
            Thread t =
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try {
                                            start.await();
                                        } catch (InterruptedException e) {
                                            return;
                                        }
                                        TenantContext.set(
                                                TenantContext.of(tenant, "sub", Set.of()));
                                        for (int n = 0; n < perSubmitter; n++) {
                                            if (ex.submit(
                                                    "test",
                                                    () -> {
                                                        int now = running.incrementAndGet();
                                                        maxRunning.accumulateAndGet(now, Math::max);
                                                        try {
                                                            Thread.sleep(2);
                                                        } catch (InterruptedException ignored) {
                                                            Thread.currentThread().interrupt();
                                                        }
                                                        running.decrementAndGet();
                                                        completed.incrementAndGet();
                                                    })) {
                                                accepted.incrementAndGet();
                                            }
                                        }
                                        TenantContext.clear();
                                    });
            threads.add(t);
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertTrue(maxRunning.get() <= global, "global cap exceeded: " + maxRunning.get());
        assertTrue(accepted.get() > 0);
        assertEquals(accepted.get(), completed.get(), "every accepted task runs exactly once");
        assertEquals(0, ex.inFlight());
        assertEquals(0, ex.trackedTenants());
    }

    @Test
    void nestedSubmitFromInsideASlotIsRejectedAtTheCapSoCallersMustRunInline() throws Exception {
        InboundChannelExecutor ex =
                new InboundChannelExecutor(Executors.newVirtualThreadPerTaskExecutor(), 4, 1, null);
        AtomicReference<Boolean> inner = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        asTenant("a");

        assertTrue(
                ex.submit(
                        "test",
                        () -> {
                            inner.set(ex.submit("test", () -> {}));
                            done.countDown();
                        }));

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse(inner.get(), "documents why the voice worker runs its chat step inline");
    }

    @Test
    void destroyWaitsForInFlightWorkThenReturnsWithoutHangingOnStuckTasks() throws Exception {
        InboundChannelExecutor ex =
                new InboundChannelExecutor(
                        1,
                        1,
                        0,
                        0L,
                        0,
                        4,
                        4,
                        "retry",
                        new org.springframework.beans.factory.ObjectProvider<>() {
                            @Override
                            public io.micrometer.core.instrument.MeterRegistry getObject() {
                                return null;
                            }

                            @Override
                            public io.micrometer.core.instrument.MeterRegistry getObject(
                                    Object... args) {
                                return null;
                            }

                            @Override
                            public io.micrometer.core.instrument.MeterRegistry getIfAvailable() {
                                return null;
                            }

                            @Override
                            public io.micrometer.core.instrument.MeterRegistry getIfUnique() {
                                return null;
                            }
                        });
        ex.shutdownWaitMs = 200;
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        asTenant("a");
        assertTrue(
                ex.submit(
                        "test",
                        () -> {
                            started.countDown();
                            try {
                                Thread.sleep(60_000);
                            } catch (InterruptedException e) {
                                interrupted.countDown();
                            }
                        }));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        long t0 = System.nanoTime();
        ex.destroy();
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertTrue(millis >= 150 && millis < 10_000, "bounded wait, was " + millis + "ms");
        assertEquals(
                10, InboundChannelExecutor.SHUTDOWN_WAIT_SECONDS, "production grace is unchanged");
        assertTrue(
                interrupted.await(5, TimeUnit.SECONDS), "stuck task is interrupted by shutdownNow");
        assertFalse(ex.submit("test", () -> {}), "closed executor sheds new work");
    }

    @Test
    void springBindsTheAutowiredConstructorAndAllProperties() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(InboundChannelExecutor.class)
                .withPropertyValues(
                        "app.omnichannel.inbound-executor.max-concurrent=12",
                        "app.omnichannel.inbound-executor.per-tenant-max-concurrent=5",
                        "app.omnichannel.inbound-executor.queue-capacity=7",
                        "app.omnichannel.inbound-executor.queue-wait-ms=1234",
                        "app.omnichannel.inbound-executor.overflow-max=9",
                        "app.omnichannel.inbound-executor.shed-mode=ack")
                .run(
                        ctx -> {
                            assertTrue(
                                    ctx.getStartupFailure() == null,
                                    String.valueOf(ctx.getStartupFailure()));
                            InboundChannelExecutor ex = ctx.getBean(InboundChannelExecutor.class);
                            assertEquals(12, ex.maxConcurrent());
                            assertEquals(5, ex.perTenantMaxConcurrent());
                            assertEquals(7, ex.queueCapacity());
                            assertEquals(1234, ex.queueWaitMs());
                            assertEquals(9, ex.overflowMax());
                            assertTrue(ex.shedAcks());
                        });
    }

    @Test
    void springDefaultsAreTheSafeOnesWithAndWithoutAMeterRegistry() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(InboundChannelExecutor.class)
                .withBean(
                        io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .run(
                        ctx -> {
                            InboundChannelExecutor ex = ctx.getBean(InboundChannelExecutor.class);
                            assertEquals(64, ex.maxConcurrent());
                            assertEquals(16, ex.perTenantMaxConcurrent());
                            assertEquals(5_000, ex.queueWaitMs());
                            assertFalse(
                                    ex.shedAcks(),
                                    "default shed-mode is retry (503), not silent drop");
                            asTenant("a");
                            assertTrue(ex.submit("t", () -> {}));
                        });
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(InboundChannelExecutor.class)
                .run(ctx -> assertTrue(ctx.getStartupFailure() == null));
    }
}
