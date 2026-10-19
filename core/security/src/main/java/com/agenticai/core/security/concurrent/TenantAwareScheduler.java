package com.agenticai.core.security.concurrent;

import java.util.concurrent.TimeUnit;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * A Reactor {@link Scheduler} that wraps every task with {@link TenantAwareExecutors#propagating}
 * on the thread that schedules it, then hands it to a delegate scheduler.
 *
 * <p>What that means for the operators: {@code subscribeOn} schedules the subscription on the
 * SUBSCRIBING thread, so the upstream subscription runs with the subscriber's tenant (what {@code
 * TenantScopedFlux} does by hand). {@code publishOn} schedules a drain from whichever thread emits
 * the signal, so the downstream runs with the emitter's tenant, which for a provider's network
 * thread is none: keep binding a captured {@code TenantScope} for tenant work after a {@code
 * publishOn}. {@code Flux.interval}/{@code delay*} keep the subscribing thread's tenant on every
 * tick. Built by {@link TenantAwareSchedulers}.
 */
public final class TenantAwareScheduler implements Scheduler {

    private final Scheduler delegate;

    TenantAwareScheduler(Scheduler delegate) {
        this.delegate = delegate;
    }

    @Override
    public Disposable schedule(Runnable task) {
        return delegate.schedule(TenantAwareExecutors.propagating(task));
    }

    @Override
    public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
        return delegate.schedule(TenantAwareExecutors.propagating(task), delay, unit);
    }

    @Override
    public Disposable schedulePeriodically(
            Runnable task, long initialDelay, long period, TimeUnit unit) {
        return delegate.schedulePeriodically(
                TenantAwareExecutors.propagating(task), initialDelay, period, unit);
    }

    @Override
    public long now(TimeUnit unit) {
        return delegate.now(unit);
    }

    @Override
    public Worker createWorker() {
        return new TenantAwareWorker(delegate.createWorker());
    }

    @Override
    public void dispose() {
        delegate.dispose();
    }

    @Override
    public boolean isDisposed() {
        return delegate.isDisposed();
    }

    @Override
    public Mono<Void> disposeGracefully() {
        return delegate.disposeGracefully();
    }

    @Override
    public void init() {
        delegate.init();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void start() {
        delegate.start();
    }

    @Override
    public String toString() {
        return "TenantAware(" + delegate + ")";
    }

    private static final class TenantAwareWorker implements Worker {

        private final Worker delegate;

        TenantAwareWorker(Worker delegate) {
            this.delegate = delegate;
        }

        @Override
        public Disposable schedule(Runnable task) {
            return delegate.schedule(TenantAwareExecutors.propagating(task));
        }

        @Override
        public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
            return delegate.schedule(TenantAwareExecutors.propagating(task), delay, unit);
        }

        @Override
        public Disposable schedulePeriodically(
                Runnable task, long initialDelay, long period, TimeUnit unit) {
            return delegate.schedulePeriodically(
                    TenantAwareExecutors.propagating(task), initialDelay, period, unit);
        }

        @Override
        public void dispose() {
            delegate.dispose();
        }

        @Override
        public boolean isDisposed() {
            return delegate.isDisposed();
        }
    }
}
