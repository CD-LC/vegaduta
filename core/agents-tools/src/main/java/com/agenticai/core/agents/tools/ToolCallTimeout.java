package com.agenticai.core.agents.tools;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Stops one tool call that outlives the turn's tool timeout ({@code toolTimeoutSeconds} of the agent's runtime
 * settings). The call runs on its own virtual thread carrying the caller's tenant, agent-version pin and MDC
 * (the tenant-aware executor); when the limit passes the thread is interrupted and the caller gets a
 * {@link ToolCallTimedOut} whose message is what the model is told. A tool that ignores the interrupt keeps
 * running in the background but can no longer hold up the turn, and what it returns afterwards is dropped.
 *
 * <p>Off (no extra thread, the call runs inline) when no limit is set, which is the platform default.
 */
final class ToolCallTimeout {

    /** The tool did not finish in time. Its message is shown to the model, so it is the configured wording. */
    static final class ToolCallTimedOut extends RuntimeException {

        ToolCallTimedOut(String message) {
            super(message);
        }
    }

    private static final ExecutorService EXECUTOR = TenantAwareExecutors.newVirtualThreadPerTaskExecutor("tool-call-");

    private final Duration limit;
    private final String message;

    /**
     * @param limit how long one call may run
     * @param messageTemplate what the model is told on a timeout; {@code {tool}} and {@code {seconds}} are filled in
     */
    ToolCallTimeout(Duration limit, String messageTemplate) {
        this.limit = limit;
        this.message = messageTemplate == null ? "Tool '{tool}' did not finish within {seconds} seconds." : messageTemplate;
    }

    /** Runs {@code call} for the tool {@code toolName}, giving up after the limit. */
    String run(String toolName, Callable<String> call) {
        Future<String> running = EXECUTOR.submit(TenantAwareExecutors.propagatingCallable(call));
        try {
            return running.get(limit.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            running.cancel(true);
            throw new ToolCallTimedOut(
                    message.replace("{tool}", toolName).replace("{seconds}", String.valueOf(limit.toSeconds())));
        } catch (InterruptedException e) {
            running.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for tool '" + toolName + "'", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }
}
