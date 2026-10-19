package com.agenticai.core.security.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.AgentVersionPin;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import reactor.core.publisher.Mono;

/** ADR-0024: everything the factory builds captures the tenant on submit and restores the worker. */
class TenantAwareExecutorsTest {

    private static final TenantContext ACME = TenantContext.of("tenant_acme", "user-1", Set.of());
    private static final TenantContext GLOBEX = TenantContext.of("tenant_globex", "user-2", Set.of());

    @AfterEach
    void clear() {
        TenantContext.clear();
        AgentVersionPin.clear();
        MDC.clear();
    }

    private static String tenant() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("none");
    }

    @Test
    void propagatingCarriesTenantPinAndMdcAndRestoresTheWorker() throws Exception {
        UUID pin = UUID.randomUUID();
        TenantContext.set(ACME);
        AgentVersionPin.set(pin);
        MDC.put("requestId", "req-1");
        Runnable wrapped =
                TenantAwareExecutors.propagating(
                        () -> {
                            assertThat(tenant()).isEqualTo("tenant_acme");
                            assertThat(AgentVersionPin.current()).isEqualTo(pin);
                            assertThat(MDC.get("requestId")).isEqualTo("req-1");
                            TenantContext.set(TenantContext.of("tenant_leak", "x", Set.of())); // forgets to clean up
                        });

        // Run on a "worker" that holds another tenant: it gets it back afterwards.
        AtomicReference<String> after = new AtomicReference<>();
        Thread worker =
                new Thread(
                        () -> {
                            TenantContext.set(GLOBEX);
                            wrapped.run();
                            after.set(tenant() + "|" + AgentVersionPin.current() + "|" + MDC.get("requestId"));
                        });
        worker.start();
        worker.join();

        assertThat(after.get()).isEqualTo("tenant_globex|null|null");
    }

    @Test
    void aPlatformTaskRunsWithNoTenantEvenOnAThreadThatHoldsOne() throws Exception {
        Runnable wrapped =
                TenantAwareExecutors.propagating(
                        () -> assertThat(tenant()).isEqualTo("none"));
        TenantContext.set(GLOBEX); // the executing thread's stale tenant
        wrapped.run();
        assertThat(tenant()).isEqualTo("tenant_globex");
    }

    @Test
    void everyExecutorKindCarriesTheSubmittersTenant() throws Exception {
        ExecutorService virtual = TenantAwareExecutors.newVirtualThreadPerTaskExecutor("t-vt-");
        ExecutorService single = TenantAwareExecutors.newSingleThreadExecutor("t-single", true);
        ThreadPoolExecutor pool =
                TenantAwareExecutors.newThreadPool(
                        1,
                        1,
                        1,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(4),
                        TenantAwareExecutors.numberedThreadFactory("t-pool-", true));
        ExecutorService cached =
                TenantAwareExecutors.newCachedThreadPool(
                        TenantAwareExecutors.threadFactory("t-cached", true));
        ScheduledExecutorService scheduled =
                TenantAwareExecutors.newSingleThreadScheduledExecutor("t-sched", true);
        try {
            TenantContext.set(ACME);
            for (ExecutorService executor : List.of(virtual, single, pool, cached, scheduled)) {
                assertThat(CompletableFuture.supplyAsync(TenantAwareExecutorsTest::tenant, executor).join())
                        .as(executor.getClass().getSimpleName())
                        .isEqualTo("tenant_acme");
                assertThat(executor.submit(TenantAwareExecutorsTest::tenant).get())
                        .isEqualTo("tenant_acme");
            }
            assertThat(scheduled.schedule(TenantAwareExecutorsTest::tenant, 1, TimeUnit.MILLISECONDS).get())
                    .isEqualTo("tenant_acme");

            // A pooled thread does not keep the tenant for the next, tenant-less submission.
            TenantContext.clear();
            for (ExecutorService executor : List.of(single, pool, scheduled)) {
                assertThat(executor.submit(TenantAwareExecutorsTest::tenant).get())
                        .isEqualTo("none");
            }
        } finally {
            for (ExecutorService executor : List.of(virtual, single, pool, cached, scheduled)) {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void aPeriodicTaskKeepsTheSchedulingThreadsTenantOnEveryRun() throws Exception {
        ScheduledExecutorService scheduled =
                TenantAwareExecutors.newSingleThreadScheduledExecutor("t-periodic", true);
        List<String> seen = new CopyOnWriteArrayList<>();
        CountDownLatch three = new CountDownLatch(3);
        try {
            TenantContext.set(ACME);
            ScheduledFuture<?> f =
                    scheduled.scheduleWithFixedDelay(
                            () -> {
                                seen.add(tenant());
                                three.countDown();
                            },
                            0,
                            1,
                            TimeUnit.MILLISECONDS);
            assertThat(three.await(5, TimeUnit.SECONDS)).isTrue();
            f.cancel(false);
        } finally {
            scheduled.shutdownNow();
        }
        assertThat(seen).hasSizeGreaterThanOrEqualTo(3).containsOnly("tenant_acme");
    }

    @Test
    void aPlatformPeriodicTaskRunsWithNoTenantEvenWhenScheduledFromATenantThread() throws Exception {
        ScheduledExecutorService scheduled =
                TenantAwareExecutors.newSingleThreadScheduledExecutor("t-platform", true);
        List<String> seen = new CopyOnWriteArrayList<>();
        CountDownLatch three = new CountDownLatch(3);
        try {
            TenantContext.set(ACME);
            ScheduledFuture<?> f =
                    scheduled.scheduleWithFixedDelay(
                            TenantAwareExecutors.platform(
                                    () -> {
                                        seen.add(tenant());
                                        three.countDown();
                                    }),
                            0,
                            1,
                            TimeUnit.MILLISECONDS);
            assertThat(three.await(5, TimeUnit.SECONDS)).isTrue();
            f.cancel(false);
        } finally {
            scheduled.shutdownNow();
        }
        assertThat(seen).hasSizeGreaterThanOrEqualTo(3).containsOnly("none");
        assertThat(TenantContext.current()).isPresent();
    }

    @Test
    void oneOffThreadsCarryTheTenantAndAreNamed() throws Exception {
        TenantContext.set(ACME);
        AtomicReference<String> seen = new AtomicReference<>();
        Thread platform =
                TenantAwareExecutors.startThread(
                        "t-one-off", true, () -> seen.set(tenant() + "@" + Thread.currentThread().getName()));
        platform.join();
        assertThat(platform.isDaemon()).isTrue();
        assertThat(seen.get()).isEqualTo("tenant_acme@t-one-off");

        Thread virtual =
                TenantAwareExecutors.startVirtualThread(
                        "t-one-off-vt", () -> seen.set(tenant() + "@" + Thread.currentThread().isVirtual()));
        virtual.join();
        assertThat(seen.get()).isEqualTo("tenant_acme@true");
    }

    @Test
    void theSpringTaskExecutorDecoratesItsTasks() throws Exception {
        SimpleAsyncTaskExecutor executor = TenantAwareExecutors.newTaskExecutor("t-app-", true);
        TenantContext.set(ACME);
        assertThat(executor.submit(TenantAwareExecutorsTest::tenant).get()).isEqualTo("tenant_acme");
        executor.close();
    }

    @Test
    void reactorSubscribeOnRunsTheSubscriptionWithTheSubscribersTenant() {
        TenantContext.set(ACME);
        String seen =
                Mono.fromCallable(TenantAwareExecutorsTest::tenant)
                        .subscribeOn(TenantAwareSchedulers.boundedElastic())
                        .block(Duration.ofSeconds(5));
        assertThat(seen).isEqualTo("tenant_acme");

        TenantContext.clear();
        String platform =
                Mono.fromCallable(TenantAwareExecutorsTest::tenant)
                        .subscribeOn(TenantAwareSchedulers.parallel())
                        .block(Duration.ofSeconds(5));
        assertThat(platform).isEqualTo("none");
    }

    @Test
    void wrappingIsIdempotent() {
        Runnable once = TenantAwareExecutors.propagating(() -> {});
        assertThat(TenantAwareExecutors.propagating(once)).isSameAs(once);
        ExecutorService virtual = TenantAwareExecutors.newVirtualThreadPerTaskExecutor("t-idem-");
        assertThat(TenantAwareExecutors.wrap(virtual)).isSameAs(virtual);
        virtual.shutdown();
    }
}
