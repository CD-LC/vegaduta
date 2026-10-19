package com.agenticai.core.security.concurrent;

import java.util.concurrent.ExecutorService;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Reactor schedulers for core code: the shared Reactor schedulers, wrapped in a {@link
 * TenantAwareScheduler} so a task carries the tenant of the thread that scheduled it (see that
 * class for what this means per operator). The shared schedulers are looked up on every call, not
 * cached, so a test that swaps them ({@code StepVerifier.withVirtualTime}) still sees its own.
 *
 * <p>Kept apart from {@link TenantAwareExecutors} so a module without reactor-core on its
 * classpath can use that class.
 */
public final class TenantAwareSchedulers {

    private TenantAwareSchedulers() {}

    /** {@code Schedulers.boundedElastic()}, tenant-aware. For blocking work. */
    public static Scheduler boundedElastic() {
        return new TenantAwareScheduler(Schedulers.boundedElastic());
    }

    /** {@code Schedulers.parallel()}, tenant-aware. For timers and short non-blocking work. */
    public static Scheduler parallel() {
        return new TenantAwareScheduler(Schedulers.parallel());
    }

    /** A scheduler over an executor built by {@link TenantAwareExecutors}. */
    public static Scheduler fromExecutorService(ExecutorService executorService, String name) {
        return new TenantAwareScheduler(Schedulers.fromExecutorService(executorService, name));
    }

    /** Wraps a scheduler the code did not create (a test's virtual-time scheduler, a library's). */
    public static Scheduler wrap(Scheduler scheduler) {
        return scheduler instanceof TenantAwareScheduler ? scheduler : new TenantAwareScheduler(scheduler);
    }
}
