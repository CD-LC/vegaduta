package com.agenticai.core.archrules.fixtures;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * One method per raw executor form {@code RawExecutorBanRulesTest} must reject, plus methods that
 * go through the tenant-aware factory and must pass. Never executed; only read as bytecode.
 */
@SuppressWarnings("all")
public class RawExecutorFixtures {

    void executorsFactory() {
        Executors.newFixedThreadPool(2);
    }

    void rawThread() {
        new Thread(() -> {}).start();
    }

    void virtualThread() {
        Thread.ofVirtual().start(() -> {});
    }

    void platformThread() {
        Thread.ofPlatform().start(() -> {});
    }

    void threadPoolConstructor() {
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
    }

    void scheduledPoolConstructor() {
        new ScheduledThreadPoolExecutor(1);
    }

    void forkJoinCommonPool() {
        ForkJoinPool.commonPool();
    }

    void springPool() {
        new ThreadPoolTaskExecutor();
    }

    void springSimpleAsync() {
        new SimpleAsyncTaskExecutor("x-");
    }

    void supplyAsyncWithoutExecutor() {
        CompletableFuture.supplyAsync(() -> 1);
    }

    void thenApplyAsyncWithoutExecutor(CompletableFuture<String> f) {
        f.thenApplyAsync(String::length);
    }

    void reactorSchedulers() {
        Mono.just(1).subscribeOn(Schedulers.boundedElastic());
    }

    // ---- allowed

    void allowedSupplyAsyncWithExecutor(ExecutorService executor) {
        CompletableFuture.supplyAsync(() -> 1, executor);
    }

    void allowedFactory() {
        ExecutorService pool = TenantAwareExecutors.newVirtualThreadPerTaskExecutor("fixture-");
        TenantAwareExecutors.startVirtualThread("fixture", () -> {});
        Mono.just(1).subscribeOn(TenantAwareSchedulers.boundedElastic());
        pool.close();
    }
}
