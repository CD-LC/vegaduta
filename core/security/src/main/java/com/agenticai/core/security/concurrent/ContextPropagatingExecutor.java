package com.agenticai.core.security.concurrent;

import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.slf4j.MDC;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * A bounded, named, virtual-thread executor that carries the submitting
 * thread's {@link TenantContext} and SLF4J {@link MDC} (requestId, tenantId,
 * ...) onto the task thread and restores the task thread's previous state
 * afterwards (JAVA-008/025).
 *
 * <p>Why: {@code CompletableFuture.supplyAsync(task)} without an executor runs
 * on {@code ForkJoinPool.commonPool()}, whose parallelism is
 * {@code availableProcessors() - 1} - ONE thread on a 2-vCPU container -
 * shared JVM-wide. Blocking LLM/HTTP work there (workflow Parallel branches
 * with sleep-based retry, inbound WhatsApp/Twilio processing, the quality
 * judge, diagnostics) silently serialized and stalled every other user of
 * the pool. Virtual threads make blocking cheap; the concurrency limit keeps
 * a burst from creating unbounded work; the task decorator keeps tenant
 * scoping and log correlation intact across the hop.
 *
 * <p>Deliberately NOT a Spring {@code TaskExecutor}: a second
 * {@code TaskExecutor} bean would make {@code @Async}'s default-executor
 * lookup ambiguous and silently change which executor every {@code @Async}
 * method uses. When the concurrency limit is reached, {@link #execute}
 * blocks the submitter until a slot frees (throttling, not rejection).
 *
 * <p>{@link #close()} stops accepting tasks and waits up to the termination
 * timeout for running ones; the Spring bean is closed on context shutdown.
 */
public final class ContextPropagatingExecutor implements Executor, AutoCloseable {

    private final SimpleAsyncTaskExecutor delegate;

    public ContextPropagatingExecutor(String threadNamePrefix, int concurrencyLimit) {
        this(threadNamePrefix, concurrencyLimit, Duration.ofSeconds(10));
    }

    public ContextPropagatingExecutor(String threadNamePrefix, int concurrencyLimit, Duration terminationTimeout) {
        if (concurrencyLimit < 1) {
            throw new IllegalArgumentException("concurrencyLimit must be >= 1");
        }
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(threadNamePrefix);
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(concurrencyLimit);
        executor.setTaskDecorator(ContextPropagatingExecutor::propagating);
        executor.setTaskTerminationTimeout(terminationTimeout.toMillis());
        this.delegate = executor;
    }

    @Override
    public void execute(Runnable task) {
        delegate.execute(task);
    }

    /** True once {@link #close()} was called. */
    public boolean isClosed() {
        return !delegate.isActive();
    }

    @Override
    public void close() {
        delegate.close();
    }

    /**
     * Wraps {@code task} so it runs with the CURRENT thread's tenant context,
     * agent-version pin and MDC, restoring whatever the executing thread had
     * before. Same as {@link TenantAwareExecutors#propagating(Runnable)}, which
     * new code should call directly (ADR-0024).
     */
    public static Runnable propagating(Runnable task) {
        return TenantAwareExecutors.propagating(task);
    }
}
