package com.agenticai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.agenticai.core.security.TenantContext;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * Locks in the exact bug fix (2026-08-15) this class was extracted for: a
 * dynamic MCP connector's {@code connect()} runs tenant-scoped DB writes
 * (markConnecting/markConnected/markErrorDiagnosis), so dispatching it onto
 * a worker thread with no {@link TenantContext} propagated would silently
 * fall back to the public schema - see this class's own javadoc.
 * {@code SimpleAsyncTaskExecutor} spins a genuinely different thread per
 * task, so these tests exercise real cross-thread propagation, not just a
 * same-thread {@code SyncTaskExecutor} stand-in.
 */
class McpConnectDispatcherTest {

    private static final UUID ID = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void dispatchPropagatesTheCallingThreadsTenantContextToTheWorkerThread() throws InterruptedException {
        TenantContext.set(TenantContext.of("tenant-xyz", "subject", Set.of()));
        McpConnectDispatcher dispatcher = new McpConnectDispatcher(new SimpleAsyncTaskExecutor());
        CountDownLatch latch = new CountDownLatch(1);
        CopyOnWriteArrayList<String> seenTenantIds = new CopyOnWriteArrayList<>();
        McpDynamicConnector connector = mock(McpDynamicConnector.class);
        doAnswer(invocation -> {
            seenTenantIds.add(TenantContext.current().map(TenantContext::getTenantId).orElse(null));
            latch.countDown();
            return null;
        }).when(connector).connect(ID);

        dispatcher.dispatch(connector, ID);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "connect() should have run on the worker thread");
        assertEquals(1, seenTenantIds.size());
        assertEquals("tenant-xyz", seenTenantIds.get(0));
    }

    @Test
    void dispatchLeavesTheCallingThreadsOwnContextUntouched() throws InterruptedException {
        TenantContext.set(TenantContext.of("tenant-caller", "subject", Set.of()));
        McpConnectDispatcher dispatcher = new McpConnectDispatcher(new SimpleAsyncTaskExecutor());
        CountDownLatch latch = new CountDownLatch(1);
        McpDynamicConnector connector = mock(McpDynamicConnector.class);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(connector).connect(ID);

        dispatcher.dispatch(connector, ID);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        Optional<TenantContext> stillOnCallingThread = TenantContext.current();
        assertTrue(stillOnCallingThread.isPresent());
        assertEquals("tenant-caller", stillOnCallingThread.get().getTenantId());
    }
}
