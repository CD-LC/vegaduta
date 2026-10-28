package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Legacy retry guard for irreversible nodes, step-timeout enforcement and parallel sibling cancellation. */
class RetryAndTimeoutEnforcementTest {

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("config", config, "label", id));
    }

    // --- retry guard ---------------------------------------------------------------

    @Test
    void irreversibleNodesAreNotRetriedByTheLegacyPath() {
        for (String type : List.of("bulk_email", "tool", "crm_lead", "device_control", "media", "workflow", "output")) {
            assertFalse(RetryPolicy.shouldRetry(node("n", type, Map.of()), 1, 3, new RuntimeException("x")), type);
        }
        assertFalse(RetryPolicy.shouldRetry(node("h", "http", Map.of("method", "POST")), 1, 3, new RuntimeException("x")));
    }

    @Test
    void safeNodesAreStillRetried() {
        assertTrue(RetryPolicy.shouldRetry(node("a", "agent", Map.of()), 1, 3, new RuntimeException("x")));
        assertTrue(RetryPolicy.shouldRetry(node("h", "http", Map.of()), 1, 3, new RuntimeException("x")));
        assertTrue(RetryPolicy.shouldRetry(node("h", "http", Map.of("method", "get")), 1, 3, new RuntimeException("x")));
    }

    @Test
    void retryUnsafeOptsAnIrreversibleNodeBackIn() {
        assertTrue(RetryPolicy.shouldRetry(
            node("b", "bulk_email", Map.of("retryUnsafe", true)), 1, 3, new RuntimeException("x")));
        assertTrue(RetryPolicy.shouldRetry(
            node("b", "http", Map.of("method", "POST", "retryUnsafe", "true")), 1, 3, new RuntimeException("x")));
    }

    // --- step timeout --------------------------------------------------------------

    private static WorkflowDefinition definition(int timeoutSeconds, int retries) {
        WorkflowDefinition def = mock(WorkflowDefinition.class);
        when(def.getId()).thenReturn(UUID.randomUUID());
        when(def.getName()).thenReturn("wf");
        when(def.getStepTimeoutSeconds()).thenReturn(timeoutSeconds);
        when(def.getMaxRetryAttempts()).thenReturn(retries);
        return def;
    }

    private static WorkflowNodeExecutor.NodeExecutionResult ok() {
        return WorkflowNodeExecutor.NodeExecutionResult.ok("out", "s", null);
    }

    private static WorkflowNodeExecutor executorAnswering(org.mockito.stubbing.Answer<?> answer) {
        WorkflowNodeExecutor executor = mock(WorkflowNodeExecutor.class);
        when(executor.executeNode(anyString(), anyString(), anyString(), any(), anyString(), any(), any(), any()))
            .thenAnswer(answer);
        return executor;
    }

    @Test
    void stepTimeoutInterruptsASlowNodeAndFailsIt() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        NodeAttemptExecutor attempts = new NodeAttemptExecutor(executorAnswering(inv -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                done.countDown();
                throw e;
            }
            return ok();
        }), null);
        long start = System.nanoTime();
        RuntimeException failure = assertThrows(RuntimeException.class, () -> attempts.execute(
            definition(1, 0), "run", node("a", "http", Map.of()), "in", "u", List.of(), RunMode.PRODUCTION));
        assertInstanceOf(NodeAttemptExecutor.NodeStepTimeoutException.class, failure);
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(interrupted.get());
    }

    @Test
    void tenantAndScopeFollowTheNodeOntoTheTimeoutWorker() {
        TenantContext tenant = TenantContext.of("tenant_a", "user", Set.of("ADMIN"));
        AtomicReference<TenantContext> seenTenant = new AtomicReference<>();
        AtomicReference<Thread> seenThread = new AtomicReference<>();
        AtomicReference<ExpressionScope> seenScope = new AtomicReference<>();
        NodeAttemptExecutor attempts = new NodeAttemptExecutor(executorAnswering(inv -> {
            seenTenant.set(TenantContext.current().orElse(null));
            seenThread.set(Thread.currentThread());
            seenScope.set(ExpressionScopeContext.current());
            return ok();
        }), null);
        TenantContext.set(tenant);
        try {
            WorkflowNodeExecutor.NodeExecutionResult r = attempts.execute(
                definition(5, 0), "run", node("a", "agent", Map.of()), "in", "u", List.of(),
                ExpressionScope.iteration("x", 0, List.of("x")), RunMode.PRODUCTION);
            assertEquals("out", r.output());
            assertEquals(tenant, seenTenant.get());
            assertNotEquals(Thread.currentThread(), seenThread.get());
            assertTrue(seenScope.get() != null, "iteration scope must be bound on the worker thread");
            assertEquals(tenant, TenantContext.current().orElse(null));
        } finally {
            TenantContext.clear();
        }
        assertNull(ExpressionScopeContext.current());
    }

    @Test
    void nodeExceptionsKeepTheirTypeAcrossTheTimeoutHop() {
        NodeAttemptExecutor attempts = new NodeAttemptExecutor(executorAnswering(inv -> {
            throw new TenantLimitExceededException("quota");
        }), null);
        assertThrows(TenantLimitExceededException.class, () -> attempts.execute(
            definition(5, 2), "run", node("a", "agent", Map.of()), "in", "u", List.of(), RunMode.PRODUCTION));
    }

    @Test
    void irreversibleNodeRunsExactlyOnceDespiteDefaultRetries() {
        AtomicInteger calls = new AtomicInteger();
        NodeAttemptExecutor attempts = new NodeAttemptExecutor(executorAnswering(inv -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }), null);
        assertThrows(IllegalStateException.class, () -> attempts.execute(
            definition(5, 2), "run", node("b", "bulk_email", Map.of()), "in", "u", List.of(), RunMode.PRODUCTION));
        assertEquals(1, calls.get());
    }

    // --- parallel runner -----------------------------------------------------------

    @Test
    void parallelRunnerCancelsSiblingsOnFirstFailure() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            ParallelBranchRunner runner = new ParallelBranchRunner(pool);
            WorkflowNodeDto slow = node("slow", "agent", Map.of());
            WorkflowNodeDto bad = node("bad", "agent", Map.of());
            CountDownLatch slowStarted = new CountDownLatch(1);
            AtomicBoolean slowInterrupted = new AtomicBoolean();
            CountDownLatch slowDone = new CountDownLatch(1);
            long start = System.nanoTime();
            ParallelBranchRunner.Joined joined = runner.run(List.of(slow, bad), n -> {
                if (n == slow) {
                    slowStarted.countDown();
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        slowInterrupted.set(true);
                    } finally {
                        slowDone.countDown();
                    }
                    return ok();
                }
                try {
                    slowStarted.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                throw new IllegalStateException("boom");
            }, 60);
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10);
            assertEquals("bad", joined.failedBranch().id());
            assertTrue(slowDone.await(5, TimeUnit.SECONDS));
            assertTrue(slowInterrupted.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelRunnerJoinIsBoundedByTheTimeout() {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            ParallelBranchRunner runner = new ParallelBranchRunner(pool);
            ParallelBranchRunner.Joined joined = runner.run(List.of(node("slow", "agent", Map.of())), n -> {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ok();
            }, 1);
            assertEquals("slow", joined.failedBranch().id());
            assertInstanceOf(NodeAttemptExecutor.NodeStepTimeoutException.class, joined.failure());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelRunnerKeepsSuccessfulOutcomesInOrder() {
        ParallelBranchRunner runner = new ParallelBranchRunner(Runnable::run);
        ParallelBranchRunner.Joined joined = runner.run(
            List.of(node("a", "agent", Map.of()), node("b", "agent", Map.of())), n -> ok(), 5);
        assertNull(joined.failure());
        assertEquals(2, joined.outcomes().size());
        assertEquals("a", joined.outcomes().get(0).node().id());
    }

    @Test
    void joinBoundCoversRetriesAndIsZeroWithoutATimeout() {
        WorkflowNodeDto http = node("a", "http", Map.of());
        assertEquals(0, ParallelStep.joinBoundSeconds(definition(0, 2), List.of(http)));
        assertEquals(10 * 3 + 30, ParallelStep.joinBoundSeconds(definition(10, 2), List.of(http)));
    }

    // --- timeout policy ------------------------------------------------------------

    @Test
    void workflowStepTimeoutOnlyBoundsFastNodeTypes() {
        assertEquals(30, NodeAttemptExecutor.effectiveTimeoutSeconds(node("h", "http", Map.of()), 30));
        assertEquals(30, NodeAttemptExecutor.effectiveTimeoutSeconds(node("c", "condition", Map.of()), 30));
        // an agent / LLM / sub-workflow node keeps working under the legacy default of 30
        for (String type : List.of("agent", "supervisor", "workflow", "tool", "code", "human_input")) {
            assertEquals(NodeAttemptExecutor.LONG_RUNNING_FLOOR_SECONDS,
                NodeAttemptExecutor.effectiveTimeoutSeconds(node("n", type, Map.of()), 30), type);
        }
        // a larger workflow value is respected for slow types
        assertEquals(900, NodeAttemptExecutor.effectiveTimeoutSeconds(node("a", "agent", Map.of()), 900));
    }

    @Test
    void perNodeTimeoutOverridesAndIsClamped() {
        assertEquals(45, NodeAttemptExecutor.effectiveTimeoutSeconds(
            node("a", "agent", Map.of("timeoutSeconds", 45)), 30));
        assertEquals(7, NodeAttemptExecutor.effectiveTimeoutSeconds(
            node("h", "http", Map.of("timeoutSeconds", "7")), 30));
        assertEquals(NodeAttemptExecutor.MAX_NODE_TIMEOUT_SECONDS, NodeAttemptExecutor.effectiveTimeoutSeconds(
            node("a", "agent", Map.of("timeoutSeconds", 999_999)), 30));
        // malformed or non-positive falls back to the type default
        assertEquals(30, NodeAttemptExecutor.effectiveTimeoutSeconds(
            node("h", "http", Map.of("timeoutSeconds", "soon")), 30));
        assertEquals(30, NodeAttemptExecutor.effectiveTimeoutSeconds(
            node("h", "http", Map.of("timeoutSeconds", 0)), 30));
    }

    @Test
    void aTimedOutAttemptIsNeverRetried() {
        WorkflowNodeDto http = node("h", "http", Map.of());
        RuntimeException timeout = new NodeAttemptExecutor.NodeStepTimeoutException(http, 5);
        assertFalse(RetryPolicy.shouldRetry(http, 1, 3, timeout));
        NodeErrorStrategy.Retry retry = new NodeErrorStrategy.Retry(3, 3, 0, false, false, false);
        assertFalse(NodeErrorStrategy.shouldRetry(retry, timeout));
    }

    @Test
    void aNodeLongerThanTheLegacyDefaultStillCompletes() {
        NodeAttemptExecutor attempts = new NodeAttemptExecutor(executorAnswering(inv -> {
            Thread.sleep(1_500);
            return ok();
        }), null);
        // workflow step timeout 1s (below the node's runtime) must not kill an agent node
        assertEquals("out", attempts.execute(definition(1, 0), "run", node("a", "agent", Map.of()), "in", "u",
            List.of(), RunMode.PRODUCTION).output());
    }
}
