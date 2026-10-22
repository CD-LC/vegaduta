package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * End-to-end through {@link WorkflowExecutionEngine}'s traversal (executor mocked, so node ORDER,
 * INPUTS and attempt COUNTS are what is pinned): the per-node error strategy's {@code continue} /
 * {@code branch} routing, retry with a per-attempt trace, the irreversible-node guard, and - the
 * property everything else relies on - that with the flag off the very same config is ignored and
 * the run fails exactly as it always did.
 */
class WorkflowExecutionEngineNodeErrorStrategyTest {

    private WorkflowExecutionEngine engine;
    private final Map<String, AtomicInteger> calls = new HashMap<>();
    private final Map<String, String> inputs = new HashMap<>();
    private final Map<String, IntFunction<RuntimeException>> failures = new HashMap<>();
    private final Map<String, WorkflowNodeExecutor.NodeExecutionResult> fixedResults =
            new HashMap<>();

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        WorkflowNodeExecutor nodeExecutor = mock(WorkflowNodeExecutor.class);
        engine =
                new WorkflowExecutionEngine(
                        mock(WorkflowRunRepository.class),
                        mock(OrchestratorService.class),
                        nodeExecutor,
                        mock(WorkflowEventPublisher.class),
                        mapper(),
                        mock(PlatformIssueService.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(WorkflowRunCancellationService.class),
                        mock(WorkflowRunPauseService.class),
                        null,
                        null,
                        new com.agenticai.core.security.concurrent.ContextPropagatingExecutor(
                                "test-branch-", 16));
        when(nodeExecutor.executeNode(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(WorkflowNodeDto.class),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenAnswer(
                        invocation -> {
                            WorkflowNodeDto node = invocation.getArgument(3);
                            String input = invocation.getArgument(4);
                            int attempt =
                                    calls.computeIfAbsent(node.id(), k -> new AtomicInteger())
                                            .incrementAndGet();
                            inputs.put(node.id(), input);
                            IntFunction<RuntimeException> failure = failures.get(node.id());
                            RuntimeException toThrow =
                                    failure == null ? null : failure.apply(attempt);
                            if (toThrow != null) {
                                throw toThrow;
                            }
                            WorkflowNodeExecutor.NodeExecutionResult fixed =
                                    fixedResults.get(node.id());
                            return fixed != null
                                    ? fixed
                                    : WorkflowNodeExecutor.NodeExecutionResult.ok(
                                            "out-" + node.id(), "ran " + node.id(), null);
                        });
    }

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("config", config, "label", id));
    }

    private static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        // Per-node legacy retry off: keeps the legacy path instant and its attempt count at one.
        m.putIfAbsent("retryAttempts", 0);
        return m;
    }

    private static Map<String, Object> retryBlock(int maxAttempts, String retryOn) {
        Map<String, Object> r = new HashMap<>();
        r.put("maxAttempts", maxAttempts);
        r.put("backoffMs", 0);
        if (retryOn != null) {
            r.put("retryOn", retryOn);
        }
        return r;
    }

    private static WorkflowEdgeDto edge(String id, String from, String to, String handle) {
        return new WorkflowEdgeDto(id, from, to, handle, null);
    }

    private static WorkflowDefinition definitionWithId() throws ReflectiveOperationException {
        WorkflowDefinition definition =
                new WorkflowDefinition(
                        "Test Workflow",
                        "desc",
                        "{\"nodes\":[],\"edges\":[]}",
                        WorkflowStatus.ACTIVE);
        java.lang.reflect.Field idField = WorkflowDefinition.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(definition, UUID.randomUUID());
        return definition;
    }

    private WorkflowRun run(WorkflowGraphDto graph) throws ReflectiveOperationException {
        return engine.execute(definitionWithId(), graph, null, "system:test");
    }

    private int callsOf(String nodeId) {
        AtomicInteger c = calls.get(nodeId);
        return c == null ? 0 : c.get();
    }

    private static WorkflowGraphDto chain(WorkflowNodeDto middle) {
        return new WorkflowGraphDto(
                List.of(node("t", "trigger", Map.of()), middle, node("b", "agent", cfg())),
                List.of(edge("e1", "t", middle.id(), null), edge("e2", middle.id(), "b", null)));
    }

    // ---- flag off: byte-identical
    // ------------------------------------------------------------------------------

    @Test
    void flagOffIgnoresOnErrorAndRetryAndTheRunFailsAsItAlwaysDid() throws Exception {
        engine.setNodeErrorStrategyEnabled(false);
        failures.put("a", attempt -> new RuntimeException("boom"));

        WorkflowRun result =
                run(
                        chain(
                                node(
                                        "a",
                                        "agent",
                                        cfg("onError", "continue", "retry", retryBlock(5, null)))));

        assertEquals(WorkflowRunStatus.FAILED, result.getStatus());
        assertEquals(1, callsOf("a"), "the retry block must be ignored");
        assertEquals(0, callsOf("b"), "onError=continue must be ignored");
        assertFalse(result.getTraceJson().contains("attempt 1/"), result.getTraceJson());
        assertFalse(result.getTraceJson().contains("onError=continue"), result.getTraceJson());
    }

    @Test
    void flagOffStillRoutesAnExistingErrorEdgeExactlyAsBefore() throws Exception {
        engine.setNodeErrorStrategyEnabled(false);
        failures.put("a", attempt -> new RuntimeException("boom"));
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("a", "agent", cfg()),
                                node("ok", "agent", cfg()),
                                node("err", "agent", cfg())),
                        List.of(
                                edge("e1", "t", "a", null),
                                edge("e2", "a", "ok", null),
                                edge("e3", "a", "err", "error")));

        WorkflowRun result = run(graph);

        assertEquals(WorkflowRunStatus.COMPLETED, result.getStatus());
        assertEquals(1, callsOf("err"));
        assertEquals(0, callsOf("ok"));
    }

    @Test
    void flagOnWithNoStrategyConfiguredIsAPlainRun() throws Exception {
        engine.setNodeErrorStrategyEnabled(true);

        WorkflowRun result = run(chain(node("a", "agent", cfg())));

        assertEquals(WorkflowRunStatus.COMPLETED, result.getStatus());
        assertEquals(List.of(1, 1, 1), List.of(callsOf("t"), callsOf("a"), callsOf("b")));
        assertEquals("out-a", inputs.get("b"));
    }

    // ---- onError: continue
    // -------------------------------------------------------------------------------------

    @Test
    void continuePassesAnErrorObjectDownstreamAndMarksTheStep() throws Exception {
        engine.setNodeErrorStrategyEnabled(true);
        failures.put("a", attempt -> new RuntimeException("boom"));

        WorkflowRun result = run(chain(node("a", "agent", cfg("onError", "continue"))));

        assertEquals(WorkflowRunStatus.COMPLETED, result.getStatus());
        JsonNode downstream = mapper().readTree(inputs.get("b"));
        assertEquals("boom", downstream.get("error").asText());
        assertEquals("a", downstream.get("nodeId").asText());
        assertTrue(result.getTraceJson().contains("\"status\":\"ERROR\""), result.getTraceJson());
        assertTrue(result.getTraceJson().contains("onError=continue"), result.getTraceJson());
    }

    @Test
    void continueDoesNotSwallowAPlanOrQuotaDenial() throws Exception {
        engine.setNodeErrorStrategyEnabled(true);
        failures.put(
                "a",
                attempt ->
                        new com.agenticai.core.security.TenantLimitExceededException(
                                "plan limit reached"));

        WorkflowRun result = run(chain(node("a", "agent", cfg("onError", "continue"))));

        assertEquals(WorkflowRunStatus.FAILED, result.getStatus());
        assertEquals(
                0,
                callsOf("b"),
                "a quota denial must stop the run, not flow downstream as an error object");
    }

    @Test
    void continueAlsoCoversANodeThatReportsFailureInsteadOfThrowing() throws Exception {
        engine.setNodeErrorStrategyEnabled(true);
        fixedResults.put(
                "a",
                WorkflowNodeExecutor.NodeExecutionResult.halted(
                        WorkflowRunStatus.FAILED, "node said no"));

        WorkflowRun result = run(chain(node("a", "agent", cfg("onError", "continue"))));

        assertEquals(WorkflowRunStatus.COMPLETED, result.getStatus());
        assertEquals("node said no", mapper().readTree(inputs.get("b")).get("error").asText());
    }

    @Test
    void continueOnTheLastNodeEndsTheRunCompleted() throws Exception {
        engine.setNodeErrorStrategyEnabled(true);
        failures.put("a", attempt -> new RuntimeException("boom"));
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("a", "agent", cfg("onError", "continue"))),
                        List.of(edge("e1", "t", "a", null)));

        assertEquals(WorkflowRunStatus.COMPLETED, run(graph).getStatus());
    }

    @Test
    void continueNeverRoutesDownTheErrorEdge() throws Exception {
        engine.setNodeErrorStrategyEnabled(true);
        failures.put("a", attempt -> new RuntimeException("boom"));
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("a", "agent", cfg("onError", "continue")),
                                node("ok", "agent", cfg()),
                                node("err", "agent", cfg())),
                        List.of(
                                edge("e1", "t", "a", null),
                                edge("e2", "a", "err", "error"),
                                edge("e3", "a", "ok", null)));

        run(graph);

        assertEquals(1, callsOf("ok"));
        assertEquals(0, callsOf("err"));
    }

    // ---- onError: branch
    // ---------------------------------------------------------------------------------------

    @Test
    void branchRoutesToTheErrorEdgeWhenOneIsWired() throws Exception {
        engine.setNodeErrorStrategyEnabled(true);
        failures.put("a", attempt -> new RuntimeException("boom"));
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("a", "agent", cfg("onError", "branch")),
                                node("ok", "agent", cfg()),
                                node("err", "agent", cfg())),
                        List.of(
                                edge("e1", "t", "a", null),
                                edge("e2", "a", "ok", null),
                                edge("e3", "a", "err", "error")));

        WorkflowRun result = run(graph);

        assertEquals(WorkflowRunStatus.COMPLETED, result.getStatus());
        assertEquals(1, callsOf("err"));
        assertEquals(0, callsOf("ok"));
        assertEquals("a", mapper().readTree(inputs.get("err")).get("failedNodeId").asText());
    }

    @Test
    void branchWithNoErrorEdgeFailsTheRun() throws Exception {
        engine.setNodeErrorStrategyEnabled(true);
        failures.put("a", attempt -> new RuntimeException("boom"));

        WorkflowRun result = run(chain(node("a", "agent", cfg("onError", "branch"))));

        assertEquals(WorkflowRunStatus.FAILED, result.getStatus());
        assertEquals(0, callsOf("b"));
    }

    // ---- retry
    // -------------------------------------------------------------------------------------------------

    @Test
    void retryRerunsUntilSuccessAndTracesEveryFailedAttempt() throws Exception {
        engine.setNodeErrorStrategyEnabled(true);
        failures.put("a", attempt -> attempt < 3 ? new RuntimeException("flaky " + attempt) : null);

        WorkflowRun result = run(chain(node("a", "agent", cfg("retry", retryBlock(3, null)))));

        assertEquals(WorkflowRunStatus.COMPLETED, result.getStatus());
        assertEquals(3, callsOf("a"));
        String trace = result.getTraceJson();
        assertTrue(trace.contains("(attempt 1/3)"), trace);
        assertTrue(trace.contains("(attempt 2/3)"), trace);
        assertFalse(
                trace.contains("(attempt 3/3)"),
                "the successful attempt is the node's normal OK entry");
        assertTrue(trace.contains("Attempt 1/3 failed: flaky 1"), trace);
    }

    @Test
    void retryThatNeverSucceedsFailsTheRunAfterExactlyMaxAttempts() throws Exception {
        engine.setNodeErrorStrategyEnabled(true);
        failures.put("a", attempt -> new RuntimeException("always"));

        WorkflowRun result = run(chain(node("a", "agent", cfg("retry", retryBlock(3, null)))));

        assertEquals(WorkflowRunStatus.FAILED, result.getStatus());
        assertEquals(3, callsOf("a"), "no legacy retries stacked on top of the configured ones");
        assertEquals(0, callsOf("b"));
    }

    @Test
    void retryThenContinueCombine() throws Exception {
        engine.setNodeErrorStrategyEnabled(true);
        failures.put("a", attempt -> new RuntimeException("always"));

        WorkflowRun result =
                run(
                        chain(
                                node(
                                        "a",
                                        "agent",
                                        cfg("retry", retryBlock(2, null), "onError", "continue"))));

        assertEquals(WorkflowRunStatus.COMPLETED, result.getStatus());
        assertEquals(2, callsOf("a"));
        assertEquals(1, callsOf("b"));
    }

    @Test
    void anIrreversibleNodeIsNotRetriedEvenWhenAskedTo() throws Exception {
        engine.setNodeErrorStrategyEnabled(true);
        failures.put("a", attempt -> new RuntimeException("smtp down"));

        WorkflowRun result = run(chain(node("a", "bulk_email", cfg("retry", retryBlock(3, null)))));

        assertEquals(WorkflowRunStatus.FAILED, result.getStatus());
        assertEquals(1, callsOf("a"));
        assertTrue(result.getTraceJson().contains("Not retried"), result.getTraceJson());
    }

    @Test
    void retryUnsafeLetsAnIrreversibleNodeRetry() throws Exception {
        engine.setNodeErrorStrategyEnabled(true);
        failures.put("a", attempt -> new RuntimeException("smtp down"));

        run(chain(node("a", "bulk_email", cfg("retry", retryBlock(3, null), "retryUnsafe", true))));

        assertEquals(3, callsOf("a"));
    }

    @Test
    void httpPostIsGuardedButHttpGetIsRetried() throws Exception {
        engine.setNodeErrorStrategyEnabled(true);
        failures.put("a", attempt -> new RuntimeException("503"));

        run(chain(node("a", "http", cfg("method", "POST", "retry", retryBlock(3, null)))));
        assertEquals(1, callsOf("a"));

        calls.clear();
        run(chain(node("a", "http", cfg("method", "GET", "retry", retryBlock(3, null)))));
        assertEquals(3, callsOf("a"));
    }

    @Test
    void transientOnlyRetriesATimeoutButNotABadConfig() throws Exception {
        engine.setNodeErrorStrategyEnabled(true);

        failures.put("a", attempt -> new RuntimeException("agentId is invalid"));
        run(chain(node("a", "agent", cfg("retry", retryBlock(3, "transient")))));
        assertEquals(1, callsOf("a"));

        calls.clear();
        failures.put("a", attempt -> new RuntimeException("upstream timed out"));
        run(chain(node("a", "agent", cfg("retry", retryBlock(3, "transient")))));
        assertEquals(3, callsOf("a"));
    }
}
