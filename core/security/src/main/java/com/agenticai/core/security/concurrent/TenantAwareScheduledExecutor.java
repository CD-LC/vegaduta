package com.agenticai.core.security.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ScheduledThreadPoolExecutor} that wraps every task with {@link
 * TenantAwareExecutors#propagating} on the thread that schedules it. {@code execute} and {@code
 * submit} delegate to {@code schedule}, so the four overrides cover every entry point. A periodic
 * task keeps the scheduling thread's tenant for every run. Built by {@link TenantAwareExecutors}.
 */
public final class TenantAwareScheduledExecutor extends ScheduledThreadPoolExecutor {

    TenantAwareScheduledExecutor(int corePoolSize, ThreadFactory threadFactory) {
        super(corePoolSize, threadFactory);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return super.schedule(TenantAwareExecutors.propagating(command), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return super.schedule(TenantAwareExecutors.propagatingCallable(callable), delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
            Runnable command, long initialDelay, long period, TimeUnit unit) {
        return super.scheduleAtFixedRate(
                TenantAwareExecutors.propagating(command), initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
            Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return super.scheduleWithFixedDelay(
                TenantAwareExecutors.propagating(command), initialDelay, delay, unit);
    }
}
