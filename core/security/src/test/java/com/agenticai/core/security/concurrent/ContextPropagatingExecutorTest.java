package com.agenticai.core.security.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.TenantContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** JAVA-008/025/026: tenant + MDC propagation, virtual threads, bounded concurrency, shutdown. */
class ContextPropagatingExecutorTest {

    private final ContextPropagatingExecutor executor = new ContextPropagatingExecutor("test-vt-", 2);

    @AfterEach
    void tearDown() {
        executor.close();
        TenantContext.clear();
        MDC.clear();
    }

    @Test
    void propagatesTenantContextAndMdcToTheTaskThread() {
        TenantContext.set(TenantContext.of("tenant_acme", "user-1", Set.of("ROLE_x")));
        MDC.put("requestId", "req-42");

        Map<String, String> seen = CompletableFuture.supplyAsync(() -> Map.of(
            "tenant", TenantContext.current().map(TenantContext::getTenantId).orElse("none"),
            "requestId", String.valueOf(MDC.get("requestId")),
            "virtual", String.valueOf(Thread.currentThread().isVirtual()),
            "name", Thread.currentThread().getName()), executor).join();

        assertThat(seen).containsEntry("tenant", "tenant_acme")
            .containsEntry("requestId", "req-42")
            .containsEntry("virtual", "true");
        assertThat(seen.get("name")).startsWith("test-vt-");
    }

    @Test
    void aTaskSubmittedWithoutContextDoesNotSeeAStaleOne() {
        String seen = CompletableFuture.supplyAsync(
            () -> TenantContext.current().map(TenantContext::getTenantId).orElse("none"), executor).join();

        assertThat(seen).isEqualTo("none");
    }

    @Test
    void inlineUseRestoresTheCallersOwnContext() {
        TenantContext.set(TenantContext.of("caller", "u", Set.of()));
        MDC.put("requestId", "outer");
        Runnable wrapped;
        try {
            TenantContext.set(TenantContext.of("captured", "u", Set.of()));
            MDC.put("requestId", "inner");
            wrapped = ContextPropagatingExecutor.propagating(() -> { });
        } finally {
            TenantContext.set(TenantContext.of("caller", "u", Set.of()));
            MDC.put("requestId", "outer");
        }

        wrapped.run();

        assertThat(TenantContext.current().map(TenantContext::getTenantId)).contains("caller");
        assertThat(MDC.get("requestId")).isEqualTo("outer");
    }

    @Test
    void concurrencyIsBounded() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<?>[] tasks = new CompletableFuture<?>[2];
        for (int i = 0; i < tasks.length; i++) {
            tasks[i] = CompletableFuture.runAsync(() -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                running.decrementAndGet();
            }, executor);
        }
        // A third submission must block the submitter while both slots are taken.
        CompletableFuture<Void> third = CompletableFuture.runAsync(
            () -> CompletableFuture.runAsync(() -> peak.accumulateAndGet(running.incrementAndGet(), Math::max), executor));
        Thread.sleep(200);
        assertThat(third).isNotDone();
        release.countDown();
        CompletableFuture.allOf(tasks).get(5, TimeUnit.SECONDS);
        third.get(5, TimeUnit.SECONDS);

        assertThat(peak.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void closeStopsTheExecutor() {
        executor.close();

        assertThat(executor.isClosed()).isTrue();
    }
}
