package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single dedicated thread every Playwright call of {@link BrowserUseSessionManager} runs on,
 * and the blocking hop onto it. Split out of the manager.
 *
 * <h2>Why every Playwright call goes through one dedicated thread</h2>
 *
 * The Playwright Java driver is NOT safe to call from more than one thread at once - Playwright,
 * Browser, BrowserContext and Page are all meant to be created and used on a single thread; the
 * Java binding talks to a background driver process over one connection and concurrent calls from
 * multiple threads can corrupt that protocol exchange. Tool calls, however, can legitimately arrive
 * from different chat-turn threads for different tenants at the same time. Routing EVERY Playwright
 * touch - session creation, navigate, click, type, read, screenshot, and the idle-session reaper's
 * sweep - through one long-lived single-threaded {@link ExecutorService} resolves that; callers
 * block on the returned {@link Future} with a timeout instead of calling Playwright directly. The
 * trade-off, stated plainly: browser actions across the WHOLE platform serialize through one
 * thread, so this is not a high-throughput path. That is the correct trade for a fallback tool
 * rather than a primary one, and it is what makes the per-tenant/global session caps race-free
 * without any extra locking - the executor thread is the sole mutator of the session map. Scaling
 * this to real parallelism later means sharding across N dedicated executor+browser pairs, not
 * adding locks to this one.
 *
 * <p>The executor is {@link TenantAwareExecutors}' single-thread executor, so the submitting
 * thread's tenant travels with each task (rule 12).
 */
final class BrowserUseActionRunner {

    private static final Logger log = LoggerFactory.getLogger(BrowserUseSessionManager.class);

    private final ExecutorService executor =
            TenantAwareExecutors.newSingleThreadExecutor("browseruse-playwright", true);

    /**
     * Runs {@code operation} on the Playwright thread and waits up to {@code timeout}. A {@link
     * BrowserUseException} from the operation is rethrown as is; anything else becomes a relayable
     * one.
     */
    <T> T run(Duration timeout, Callable<T> operation) {
        Future<T> future = executor.submit(operation);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BrowserUseException("the browser action timed out");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BrowserUseException browserUseException) {
                throw browserUseException;
            }
            log.warn("browser-use action failed", cause);
            throw new BrowserUseException(
                    "the browser action failed: " + safeMessage(cause == null ? e : cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrowserUseException("the browser action was interrupted");
        }
    }

    /** Queues {@code operation} without waiting (its result and failure are not observed). */
    void submit(Callable<?> operation) {
        executor.submit(operation);
    }

    /** Queues {@code task}; an exception it throws goes to the executor's handler, not a Future. */
    void execute(Runnable task) {
        executor.execute(task);
    }

    /** Runs {@code last} on the thread, then stops it (waits up to 10s, then interrupts). */
    void shutdown(Callable<?> last) {
        executor.submit(last);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
