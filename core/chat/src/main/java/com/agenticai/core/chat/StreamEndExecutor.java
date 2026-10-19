package com.agenticai.core.chat;

import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import io.micrometer.core.instrument.Metrics;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs the best-effort work that happens when a chat stream ends (the output-guardrail audit, the
 * {@code agent.message.completed} event, the RL episode close, the LLM call-log row) off the thread
 * that delivered the last token.
 *
 * <p>That thread belongs to the HTTP client that streams the provider's reply, so a database round
 * trip on it is time the connection is not serving other streams. With this executor on, those
 * writes queue and a small fixed pool runs them.
 *
 * <p><b>Off by default</b> ({@code app.chat.stream-end.async=false}): with it off {@link #run} is a
 * plain method call and nothing here changes. Turn it on only after measuring (see {@code
 * docs/architecture/HOT-PATH-PERFORMANCE.md}).
 *
 * <p><b>Rules the callers can rely on</b>
 *
 * <ul>
 *   <li><b>Bounded.</b> A fixed pool and a bounded queue. When the queue is full the CALLER runs
 *       the work inline: backpressure instead of unbounded memory, and nothing is dropped.
 *   <li><b>Nothing is lost on a graceful stop.</b> This is a {@link SmartLifecycle} bean that stops
 *       after the web server has finished its in-flight requests and before the database pool is
 *       closed; it drains the queue (up to {@code drain-timeout}). After the stop begins, work runs
 *       inline.
 *   <li><b>Not ordered.</b> Tasks run in any order on any pool thread. Work that must happen before
 *       the next turn can start (appending the reply to the session history) is NOT handed to this
 *       executor.
 *   <li><b>Tenant context is the task's business.</b> A pool task runs with the {@link
 *       TenantContext} of the thread that submitted it (usually the HTTP client's: none); a task
 *       that needs one re-applies the snapshot it captured on the request thread (as these tasks
 *       already did when they ran on the HTTP client's thread), and the pool thread's tenant and
 *       agent-version pin are restored after every task (the pool comes from {@code
 *       TenantAwareExecutors}). A task the executor runs inline on the caller
 *       (executor off, queue full, stopping) leaves the caller's own tenant context exactly as it
 *       was; tasks that re-apply a snapshot use {@code TenantScope}, which restores the previous
 *       context.
 * </ul>
 *
 * <p>What is NOT guaranteed: a hard kill (SIGKILL, OOM, node loss) loses whatever is still queued.
 * With the default queue that is at most a few hundred best-effort rows.
 */
@Component
public class StreamEndExecutor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StreamEndExecutor.class);
    private static final RateLimitedLog SATURATED_LOG =
            new RateLimitedLog(log, Duration.ofMinutes(1));

    /**
     * Runs everything inline on the caller; the default for hand-built collaborators and the
     * flag-off state.
     */
    public static final StreamEndExecutor INLINE =
            new StreamEndExecutor(false, 0, 0, Duration.ZERO);

    private final boolean async;
    private final Duration drainTimeout;
    private final ThreadPoolExecutor pool;
    private volatile boolean running;

    public StreamEndExecutor(
            @Value("${app.chat.stream-end.async:false}") boolean async,
            @Value("${app.chat.stream-end.threads:4}") int threads,
            @Value("${app.chat.stream-end.queue:1000}") int queueCapacity,
            @Value("${app.chat.stream-end.drain-timeout:10s}") Duration drainTimeout) {
        this.async = async && threads > 0 && queueCapacity > 0;
        this.drainTimeout = drainTimeout == null ? Duration.ofSeconds(10) : drainTimeout;
        if (this.async) {
            RejectedExecutionHandler inlineWhenFull =
                    (task, executor) -> {
                        // Queue full, or the pool is already stopping: run on the caller. Never
                        // drop.
                        Metrics.counter(
                                        "chat.stream_end.inline",
                                        "reason",
                                        executor.isShutdown() ? "stopping" : "queue_full")
                                .increment();
                        if (!executor.isShutdown()) {
                            SATURATED_LOG.warn(
                                    "stream-end-full",
                                    "Stream-end queue is full ("
                                            + queueCapacity
                                            + "); running stream-end work on the streaming thread"
                                            + " until it drains");
                        }
                        task.run();
                    };
            this.pool =
                    TenantAwareExecutors.newThreadPool(
                            threads,
                            threads,
                            30,
                            TimeUnit.SECONDS,
                            new LinkedBlockingQueue<>(queueCapacity),
                            TenantAwareExecutors.numberedThreadFactory("chat-stream-end-", true),
                            // The tenant-aware pool restores the pool thread's (empty) tenant and
                            // agent-version pin after every task (ADR-0024), so a task that set
                            // either cannot leak it into the next task's rows.
                            inlineWhenFull);
            this.pool.allowCoreThreadTimeOut(true);
        } else {
            this.pool = null;
        }
    }

    /**
     * Runs {@code work} now on this thread (executor off, queue full, stopping) or soon on a pool
     * thread. {@code work} must catch its own failures (all current callers already do); a leaked
     * one is logged.
     */
    public void run(Runnable work) {
        if (!async || pool == null) {
            work.run();
            return;
        }
        pool.execute(
                () -> {
                    try {
                        work.run();
                    } catch (Throwable t) {
                        log.warn("Stream-end task failed", t);
                    }
                });
    }

    /** Tasks queued or running right now; for tests and the drain log. */
    public int pending() {
        return pool == null ? 0 : pool.getQueue().size() + pool.getActiveCount();
    }

    public boolean isAsync() {
        return async;
    }

    // ---- lifecycle: drain after the web server's graceful shutdown, before the datasource closes
    // ----

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn(
                        "Stream-end drain timed out after {} with {} task(s) not run",
                        drainTimeout,
                        pool.getQueue().size());
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Below the web server's graceful-shutdown phase, so it stops (and drains) after in-flight
     * streams end.
     */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 2048;
    }
}
