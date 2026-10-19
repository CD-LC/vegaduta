package com.agenticai.core.agents.coding;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bounded in-process execution for coding tasks, the same shape and argument as {@code
 * AgentLabExecutor}: two platform threads and a queue of four. A task holds a worker for minutes
 * (sandbox boot, test runs, model calls, and the wait for a human), so the bound is the feature - an
 * unbounded pool would let one tenant's burst starve everyone else's. A rejected submission is
 * surfaced as 429 rather than parked, because nothing would ever pick a parked task back up.
 */
@Component
public class CodingTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(CodingTaskExecutor.class);

    private final ThreadPoolExecutor executor;

    public CodingTaskExecutor() {
        AtomicInteger counter = new AtomicInteger();
        this.executor = TenantAwareExecutors.newThreadPool(
            2, 2, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(4),
            TenantAwareExecutors.numberedThreadFactory("coding-task-", true));
        this.executor.allowCoreThreadTimeOut(true);
    }

    /** @return true when accepted; false when the pool and queue are full */
    public boolean trySubmit(Runnable body) {
        try {
            executor.execute(body);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("Coding-task executor saturated ({} active, {} queued) - rejecting submission",
                executor.getActiveCount(), executor.getQueue().size());
            return false;
        }
    }

    /** Takes a task that has not started yet back out of the queue. */
    public boolean remove(Runnable body) {
        return executor.remove(body);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
