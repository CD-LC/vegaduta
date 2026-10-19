package com.agenticai.core.archrules.fixtures;

import com.agenticai.core.security.TenantScope;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * One method per kind of async hop {@code AsyncHopTenantScopeRulesTest} must recognise, plus two
 * that go through the approved wrapper and must not be reported. Never executed; only read as
 * bytecode.
 */
@SuppressWarnings("all")
public class AsyncHopFixtures {

    void supplyAsync() {
        CompletableFuture.supplyAsync(() -> 1);
    }

    void runAsync() {
        CompletableFuture.runAsync(() -> {});
    }

    void thenApplyAsync(CompletableFuture<String> f) {
        f.thenApplyAsync(String::length);
    }

    void newPool() {
        Executors.newFixedThreadPool(2);
    }

    void rawThread() {
        new Thread(() -> {}).start();
    }

    void virtualThread() {
        Thread.ofVirtual().start(() -> {});
    }

    void executorSubmit(ExecutorService executor) {
        executor.submit(() -> 1);
    }

    void executorExecute(java.util.concurrent.Executor executor) {
        executor.execute(() -> {});
    }

    void reactorHops() {
        Mono.just(1).subscribeOn(Schedulers.boundedElastic()).publishOn(Schedulers.parallel());
    }

    void wrappedHop(ExecutorService executor) {
        TenantScope scope = TenantScope.capture();
        executor.submit(scope.wrapRunnable(() -> {}));
    }

    void wrappedHopInsideLambdas(ExecutorService executor) {
        TenantScope scope = TenantScope.capture();
        Runnable outer = () -> executor.submit(() -> scope.run(() -> {}));
        outer.run();
    }
}
