package com.agenticai.core.security.concurrent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ThreadPoolExecutor} that wraps every task with {@link TenantAwareExecutors#propagating}
 * on the submitting thread. {@code submit}/{@code invokeAll} funnel through {@link #execute}, so
 * one override covers them. The queue (and a {@link RejectedExecutionHandler}) sees the wrapped
 * task. Built by {@link TenantAwareExecutors#newThreadPool}.
 */
public final class TenantAwareThreadPoolExecutor extends ThreadPoolExecutor {

    TenantAwareThreadPoolExecutor(
            int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue,
            ThreadFactory threadFactory,
            RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);
    }

    @Override
    public void execute(Runnable command) {
        super.execute(TenantAwareExecutors.propagating(command));
    }
}
