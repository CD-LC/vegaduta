package com.agenticai.core.tenancy.replica;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Puts a hard time bound on a SYNCHRONOUS Redis interaction used by the opt-in replica-state stores
 * ({@code app.sandbox.data-analysis.store=redis}, {@code app.sandbox.platform-e2b.quota-store=redis},
 * {@code app.guardrails.procedures.session-calls-store=redis}, {@code app.demo.studio.approval-store=redis}).
 *
 * <p>Why: {@code application.yml} sets no {@code spring.data.redis.timeout}, so Lettuce's command timeout is its 60 s
 * default. A black-holed connection (for example Azure's 4 minute idle NAT drop, which sends no FIN or RST) would stall
 * the user's tool call for 60 s per command. Those stores promise to fall back to the old per-JVM behaviour when Redis
 * cannot be used; that promise only holds for FAST failures unless the wait itself is bounded.
 *
 * <p>{@link #call} runs the action on a small daemon pool and waits at most {@code timeoutMillis}. Everything that is
 * not a plain result is reported as an unchecked exception, so callers keep their existing
 * {@code catch (RuntimeException)} "Redis is unavailable, use the old behaviour" branch:
 * <ul>
 *   <li>the action's own {@link RuntimeException} is rethrown as is;</li>
 *   <li>a timeout, a saturated pool (every worker still stuck on a hung command) and an open breaker throw
 *       {@link RedisCallUnavailableException}.</li>
 * </ul>
 * After a timeout the breaker stays open for {@code breakerMillis} (default 5 s): further calls fail immediately instead
 * of each paying the full wait while Redis is down. The pool has no queue (a hand-off), so a flood of callers cannot
 * pile up behind hung commands either; a caller that finds no free worker simply gets the fallback.
 */
public final class BoundedRedisCall {

    /** Default bound for a single small command or a couple of them. */
    public static final long DEFAULT_TIMEOUT_MILLIS = 250L;
    /** How long the breaker stays open after a timeout. */
    public static final long DEFAULT_BREAKER_MILLIS = 5_000L;
    private static final int DEFAULT_MAX_THREADS = 8;

    /** Thrown when the bounded call timed out, the pool is saturated or the breaker is open. */
    public static final class RedisCallUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RedisCallUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final ThreadPoolExecutor pool;
    private final long breakerMillis;
    private final LongSupplier nanoClock;
    private volatile long openUntilNanos;
    private volatile boolean open;

    public BoundedRedisCall(String threadName) {
        this(threadName, DEFAULT_MAX_THREADS, DEFAULT_BREAKER_MILLIS, System::nanoTime);
    }

    BoundedRedisCall(String threadName, int maxThreads, long breakerMillis, LongSupplier nanoClock) {
        this.breakerMillis = Math.max(0L, breakerMillis);
        this.nanoClock = nanoClock;
        this.pool = TenantAwareExecutors.newThreadPool(0, Math.max(1, maxThreads), 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
            TenantAwareExecutors.threadFactory(threadName, true));
    }

    /** Runs {@code action} and waits at most {@code timeoutMillis}; see the class comment for what is thrown. */
    public <T> T call(long timeoutMillis, Callable<T> action) {
        if (open) {
            if (nanoClock.getAsLong() - openUntilNanos < 0) {
                throw new RedisCallUnavailableException("Redis calls are paused after a timeout", null);
            }
            open = false;
        }
        Future<T> future;
        try {
            future = pool.submit(action);
        } catch (RejectedExecutionException e) {
            throw new RedisCallUnavailableException("every Redis worker is busy (hung commands?)", e);
        }
        try {
            return future.get(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            openUntilNanos = nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(breakerMillis);
            open = true;
            throw new RedisCallUnavailableException("Redis did not answer within " + timeoutMillis + " ms", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RedisCallUnavailableException("interrupted while waiting for Redis", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RedisCallUnavailableException("Redis call failed", cause);
        }
    }

    /** Void variant of {@link #call}. */
    public void run(long timeoutMillis, Runnable action) {
        call(timeoutMillis, () -> {
            action.run();
            return null;
        });
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
