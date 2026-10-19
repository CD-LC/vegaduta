package com.agenticai.core.mcp;

import com.agenticai.core.security.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Runs {@link McpDynamicConnector#connect(UUID)} off the calling thread,
 * carrying the calling thread's {@link TenantContext} over to the worker
 * thread exactly like {@code TenantContextPropagatingToolCallback} does for
 * tool calls - {@code connect()}'s own DB writes (markConnecting/
 * markConnected/markErrorDiagnosis) are all tenant-schema-scoped and would
 * silently fall back to the public schema without this.
 *
 * <p>Extracted (2026-08-15) from a private method that started life inside
 * {@code McpRegistryController} - a real bug there (register()/toggle()
 * blocking the HTTP response on a slow remote MCP handshake, racing a
 * client/proxy timeout into a misleading generic error) needed exactly this
 * fix, and the same fix is needed again for {@code AtlassianOAuthController}'s
 * callback, which must NOT dispatch from inside the {@code @Transactional}
 * method that persists the new registration/credential rows - dispatching
 * there would race the transaction's own commit against this class's worker
 * thread reading the not-yet-committed rows. Both callers dispatch AFTER
 * their own persistence transaction has returned (and therefore committed).
 *
 * <p>{@code connect()} itself has a documented "never throws" contract
 * (catches everything, persists a structured diagnosis), so the only
 * failure mode this class guards against is that contract itself being
 * violated by a future bug - never silently swallowed.
 */
@Component
public class McpConnectDispatcher {

    private static final Logger log = LoggerFactory.getLogger(McpConnectDispatcher.class);

    private final TaskExecutor executor;

    public McpConnectDispatcher(
        // Qualified: this app's virtual-thread task-executor profile
        // registers BOTH applicationTaskExecutor and taskScheduler as
        // TaskExecutor-compatible beans - an unqualified injection here is
        // genuinely ambiguous, not just noisy.
        @Qualifier("applicationTaskExecutor") TaskExecutor executor
    ) {
        this.executor = executor;
    }

    public void dispatch(McpDynamicConnector connector, UUID registrationId) {
        Optional<TenantContext> capturedContext = TenantContext.current();
        executor.execute(() -> {
            Optional<TenantContext> previous = TenantContext.current();
            capturedContext.ifPresent(TenantContext::set);
            try {
                connector.connect(registrationId);
            } catch (Throwable t) {
                // Belt-and-suspenders only - connect() already guarantees it
                // never throws. Never let a bug in that contract silently
                // kill this background thread with no trace anywhere.
                log.error("Unexpected exception escaped McpDynamicConnector#connect({}) despite its never-throws contract", registrationId, t);
            } finally {
                previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
            }
        });
    }
}
