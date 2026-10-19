package com.agenticai.core.security.concurrent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;

/**
 * The one place in core that creates threads, thread pools and schedulers (ADR-0024). Everything
 * it hands out carries the submitting thread's tenant to the worker:
 *
 * <ul>
 *   <li><b>Capture on submit.</b> Every {@code execute}, {@code submit}, {@code invokeAll}, {@code
 *       schedule*} call captures the calling thread's {@code TenantContext}, {@code
 *       AgentVersionPin} and MDC at that moment.
 *   <li><b>Apply on the worker, always restore.</b> The task runs with exactly that state, and the
 *       worker's previous state is put back in {@code finally}, so a pooled thread never carries a
 *       tenant from one task into the next.
 *   <li><b>Platform tasks run with no tenant.</b> A task submitted from a thread without a tenant
 *       (a start-up hook, a timer, a Kafka listener) runs with the tenant explicitly cleared, never
 *       with whatever a pooled thread happened to hold. Such a task binds a tenant itself when it
 *       needs one ({@code TenantScope.of(..)}, {@code TenantSchemaIterator}).
 *   <li><b>Periodic tasks keep the scheduling thread's state</b> for every run, so a periodic job
 *       is normally scheduled from a tenant-less thread (construction, start-up) and binds tenants
 *       per tick. A pod-wide job that may be scheduled lazily from a request thread must wrap its
 *       task in {@link #platform(Runnable)}, or it keeps the first request's tenant forever.
 * </ul>
 *
 * <p>Raw {@code Executors.*}, {@code new Thread}, {@code Thread.ofVirtual/ofPlatform}, pool
 * constructors, executor-less {@code CompletableFuture.*Async} and Reactor {@code Schedulers.*}
 * are banned outside this package by {@code RawExecutorBanRulesTest} (core/app). Reactor
 * schedulers come from {@link TenantAwareSchedulers}.
 *
 * <p>The {@link ThreadFactory} methods name threads and set the daemon flag only; they do NOT
 * propagate anything (a pool's worker thread outlives the task that made the pool create it). Use
 * them to configure a pool built here, not to start threads by hand: use {@link #startThread} or
 * {@link #startVirtualThread} for that.
 */
public final class TenantAwareExecutors {

    private TenantAwareExecutors() {}

    // ---------------------------------------------------------------- task wrapping

    /**
     * Wraps {@code task} so it runs with the CURRENT thread's tenant, agent-version pin and MDC
     * (none, if the current thread has none) and restores the executing thread afterwards.
     */
    public static Runnable propagating(Runnable task) {
        if (task instanceof PropagatingRunnable) {
            return task;
        }
        return new PropagatingRunnable(ContextSnapshot.capture(), task);
    }

    /**
     * Wraps {@code task} as a platform task: it runs with NO tenant, agent-version pin or MDC,
     * whatever thread submits or schedules it, and the executing thread is restored afterwards. The
     * executors built here keep this wrapping as is (they do not re-capture the submitter's state).
     * Use it for pod-wide work that is started lazily from a request thread, such as a periodic
     * poller serving every tenant.
     */
    public static Runnable platform(Runnable task) {
        return new PropagatingRunnable(ContextSnapshot.EMPTY, task);
    }

    /** {@link #propagating(Runnable)} for a {@link Callable} (a separate name, so a value-returning lambda is never silently taken for one). */
    public static <T> Callable<T> propagatingCallable(Callable<T> task) {
        ContextSnapshot snapshot = ContextSnapshot.capture();
        return () -> snapshot.call(task);
    }

    /** A Spring {@link TaskDecorator} that does {@link #propagating(Runnable)}. */
    public static TaskDecorator taskDecorator() {
        return TenantAwareExecutors::propagating;
    }

    private record PropagatingRunnable(ContextSnapshot snapshot, Runnable task) implements Runnable {
        @Override
        public void run() {
            snapshot.run(task);
        }
    }

    // ---------------------------------------------------------------- thread factories

    /** Platform threads all named exactly {@code name} (for single-thread pools). */
    public static ThreadFactory threadFactory(String name, boolean daemon) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(daemon);
            return thread;
        };
    }

    /** Platform threads named {@code prefix1}, {@code prefix2}, ... */
    public static ThreadFactory numberedThreadFactory(String prefix, boolean daemon) {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(daemon);
            return thread;
        };
    }

    /** Virtual threads named {@code prefix0}, {@code prefix1}, ... */
    public static ThreadFactory virtualThreadFactory(String prefix) {
        return Thread.ofVirtual().name(prefix, 0).factory();
    }

    // ---------------------------------------------------------------- one-off threads

    /** Starts a platform thread named {@code name} that runs {@code task} with the caller's tenant. */
    public static Thread startThread(String name, boolean daemon, Runnable task) {
        Thread thread = new Thread(propagating(task), name);
        thread.setDaemon(daemon);
        thread.start();
        return thread;
    }

    /** Starts a virtual thread named {@code name} that runs {@code task} with the caller's tenant. */
    public static Thread startVirtualThread(String name, Runnable task) {
        return Thread.ofVirtual().name(name).start(propagating(task));
    }

    // ---------------------------------------------------------------- executors

    /** One new virtual thread per task, named {@code prefix0}, {@code prefix1}, ... */
    public static ExecutorService newVirtualThreadPerTaskExecutor(String prefix) {
        return new TenantAwareExecutorService(
                Executors.newThreadPerTaskExecutor(virtualThreadFactory(prefix)));
    }

    /** A single platform worker thread named {@code name}, unbounded queue. */
    public static ExecutorService newSingleThreadExecutor(String name, boolean daemon) {
        return new TenantAwareExecutorService(
                Executors.newSingleThreadExecutor(threadFactory(name, daemon)));
    }

    /** {@code nThreads} platform worker threads, unbounded queue. */
    public static ThreadPoolExecutor newFixedThreadPool(int nThreads, ThreadFactory threadFactory) {
        return newThreadPool(
                nThreads,
                nThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Unbounded cached pool: a new thread when none is idle, idle threads die after 60 s. */
    public static ThreadPoolExecutor newCachedThreadPool(ThreadFactory threadFactory) {
        return newThreadPool(
                0,
                Integer.MAX_VALUE,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** {@link #newThreadPool(int, int, long, TimeUnit, BlockingQueue, ThreadFactory, RejectedExecutionHandler)} with the default abort policy. */
    public static ThreadPoolExecutor newThreadPool(
            int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue,
            ThreadFactory threadFactory) {
        return newThreadPool(
                corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                unit,
                workQueue,
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** A {@link ThreadPoolExecutor} with exactly these settings that propagates the tenant. */
    public static ThreadPoolExecutor newThreadPool(
            int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue,
            ThreadFactory threadFactory,
            RejectedExecutionHandler handler) {
        return new TenantAwareThreadPoolExecutor(
                corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);
    }

    /** A single-thread scheduler whose thread is named {@code name}. */
    public static ScheduledExecutorService newSingleThreadScheduledExecutor(
            String name, boolean daemon) {
        return newScheduledThreadPool(1, threadFactory(name, daemon));
    }

    /** A scheduled pool with {@code corePoolSize} threads that propagates the tenant. */
    public static TenantAwareScheduledExecutor newScheduledThreadPool(
            int corePoolSize, ThreadFactory threadFactory) {
        return new TenantAwareScheduledExecutor(corePoolSize, threadFactory);
    }

    /**
     * Wraps an executor the code did not create (a library's, a test's direct executor) so that
     * every task submitted to it carries the tenant.
     */
    public static ExecutorService wrap(ExecutorService delegate) {
        if (delegate instanceof TenantAwareExecutorService
                || delegate instanceof TenantAwareThreadPoolExecutor
                || delegate instanceof TenantAwareScheduledExecutor) {
            return delegate;
        }
        return new TenantAwareExecutorService(delegate);
    }

    /**
     * A Spring {@code SimpleAsyncTaskExecutor} (a new thread per task, virtual when {@code
     * virtualThreads}) whose task decorator propagates the tenant. For {@code TaskExecutor} beans
     * such as {@code applicationTaskExecutor}, which {@code @Async} methods run on.
     */
    public static SimpleAsyncTaskExecutor newTaskExecutor(String threadNamePrefix, boolean virtualThreads) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(threadNamePrefix);
        executor.setVirtualThreads(virtualThreads);
        executor.setTaskDecorator(taskDecorator());
        return executor;
    }
}
