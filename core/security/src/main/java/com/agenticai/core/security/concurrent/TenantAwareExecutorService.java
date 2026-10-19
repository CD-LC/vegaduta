package com.agenticai.core.security.concurrent;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * An {@link ExecutorService} that wraps every task with {@link TenantAwareExecutors#propagating}
 * on the submitting thread and hands it to a delegate. {@code submit}/{@code invokeAll}/{@code
 * invokeAny} all funnel through {@link #execute}. Built by {@link TenantAwareExecutors}.
 */
public final class TenantAwareExecutorService extends AbstractExecutorService {

    private final ExecutorService delegate;

    TenantAwareExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(TenantAwareExecutors.propagating(command));
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
