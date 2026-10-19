package com.agenticai.core.omnichannel.whatsappvoice;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs one blocking call (a Sarvam speech request) on a small bounded daemon pool and gives up after
 * a deadline, because the Sarvam clients are plain {@code RestClient} calls with no per-call timeout
 * of their own and a hung upstream must not pin the request thread of a customer's chat.
 *
 * <p>Honest limits: on timeout the future is cancelled (thread interrupted) but a blocking socket read
 * is not guaranteed to unblock, so a worker may linger until the HTTP stack gives up. That is why the
 * pool is bounded with a bounded queue: when it is full {@link #call} throws
 * {@link RejectedExecutionException} immediately (reported as an overload) instead of queueing without
 * limit - no retry storms, no unbounded threads.
 *
 * <p>The caller's {@link TenantContext} is captured and re-applied on the worker (the Sarvam
 * credential lookup is schema-per-tenant) and always cleared afterwards.
 */
final class TimeBoundedRunner implements AutoCloseable {

    private final ThreadPoolExecutor pool;

    TimeBoundedRunner(int threads, int queueCapacity) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = TenantAwareExecutors.numberedThreadFactory("whatsapp-voice-note-", true);
        this.pool = TenantAwareExecutors.newThreadPool(threads, threads, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
        this.pool.allowCoreThreadTimeOut(true);
    }

    <T> T call(Duration timeout, Callable<T> task) throws Exception {
        Optional<TenantContext> tenant = TenantContext.current();
        Future<T> future = pool.submit(() -> {
            tenant.ifPresent(TenantContext::set);
            try {
                return task.call();
            } finally {
                if (tenant.isPresent()) {
                    TenantContext.clear();
                }
            }
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
