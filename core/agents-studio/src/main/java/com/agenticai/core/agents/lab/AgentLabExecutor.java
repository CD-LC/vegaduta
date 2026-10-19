package com.agenticai.core.agents.lab;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bounded in-process execution for Agent Lab runs. Same shape and the same
 * argument as {@code EvalRunExecutor} in core/quality (which this module
 * cannot depend on): two platform threads and a queue of eight.
 *
 * <p>A lab run is up to 120 serial model calls. The bound is the feature: an
 * unbounded executor would let one tenant's burst of runs take the shared
 * hosted pool and the BYOK rate limit from everyone's real chat turns.
 * Rejection is surfaced to the caller (429) rather than parked, because
 * nothing would ever pick a parked run back up.
 *
 * <p>No boot orphan sweep, unlike the eval executor: run state lives in Redis
 * with a TTL, not in a table, and a run whose JVM died is failed by the
 * next poll that finds its liveness mark gone ({@code AgentLabRunService#get}).
 */
@Component
public class AgentLabExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentLabExecutor.class);

    private final ThreadPoolExecutor executor;

    public AgentLabExecutor() {
        this.executor = TenantAwareExecutors.newThreadPool(
            2, 2, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(8),
            // Daemon: a lab run must never hold the JVM open on shutdown.
            TenantAwareExecutors.numberedThreadFactory("agent-lab-", true));
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * @return true when accepted; false when the pool and queue are full -
     *     the caller owns deleting the run it just queued and answering 429
     */
    public boolean trySubmit(Runnable runBody) {
        try {
            executor.execute(runBody);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("Agent-lab executor saturated ({} active, {} queued) - rejecting submission",
                executor.getActiveCount(), executor.getQueue().size());
            return false;
        }
    }

    /**
     * Takes a run that has not started yet back out of the queue, so a run
     * that was cancelled (or timed out) while waiting stops holding one of
     * the eight slots until a worker gets round to discarding it.
     *
     * @param runBody the same instance that was passed to {@link #trySubmit}
     * @return false when it was not waiting in this JVM's queue - already
     *     started, already finished, or queued on another replica
     */
    public boolean remove(Runnable runBody) {
        return executor.remove(runBody);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
