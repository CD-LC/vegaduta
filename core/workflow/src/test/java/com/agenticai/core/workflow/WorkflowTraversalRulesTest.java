package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkflowTraversalRules} runs inside the Temporal workflow class, so it must be deterministic: same
 * input, same answer, no hidden state, and no route to a clock, random source or I/O.
 */
class WorkflowTraversalRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static WorkflowNodeDto node(Map<String, Object> config) {
        return new WorkflowNodeDto("n", "loop", Map.of(), Map.of("config", config, "label", "L"));
    }

    private static WorkflowEdgeDto edge(String target, String handle) {
        return new WorkflowEdgeDto("e-" + target, "n", target, handle, null);
    }

    @Test
    void sameInputGivesSameAnswerAcrossManyCalls() {
        WorkflowNodeDto n = node(Map.of("maxIterations", 7, "itemsField", "rows"));
        List<WorkflowEdgeDto> edges = List.of(edge("a", "error"), edge("b", "x"), edge("c", null));
        String input = "{\"rows\":[1,\"two\",{\"k\":3}]}";
        String firstNext = WorkflowTraversalRules.nextNodeId(edges, null);
        String firstTemporal = WorkflowTraversalRules.nextNodeIdFirstEdge(edges, null);
        List<String> firstItems = WorkflowTraversalRules.resolveLoopItems(MAPPER, n, input);
        for (int i = 0; i < 200; i++) {
            assertEquals(firstNext, WorkflowTraversalRules.nextNodeId(edges, null));
            assertEquals(firstTemporal, WorkflowTraversalRules.nextNodeIdFirstEdge(edges, null));
            assertEquals(firstItems, WorkflowTraversalRules.resolveLoopItems(MAPPER, n, input));
            assertEquals(7, WorkflowTraversalRules.loopMaxIterations(n));
        }
        assertEquals(List.of("1", "two", "{\"k\":3}"), firstItems);
    }

    @Test
    void rulesClassHoldsNoMutableState() {
        for (Field f : WorkflowTraversalRules.class.getDeclaredFields()) {
            assertTrue(Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers()),
                "non-constant field would be replay-unsafe: " + f.getName());
        }
    }

    @Test
    void rulesClassReferencesNoClockRandomOrIo() throws Exception {
        String resource = WorkflowTraversalRules.class.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = WorkflowTraversalRules.class.getClassLoader().getResourceAsStream(resource)) {
            bytes = in.readAllBytes();
        }
        // Constant-pool class and method references appear as UTF-8 strings in the class file.
        String pool = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String forbidden : List.of("java/time/", "java/util/Random", "ThreadLocalRandom", "SecureRandom",
            "java/lang/System", "java/util/UUID", "java/io/File", "java/nio/file", "java/net/", "java/lang/Thread",
            "org/slf4j")) {
            assertFalse(pool.contains(forbidden), "rules must not reference " + forbidden);
        }
    }

    @Test
    void temporalNextHopStillTakesTheFirstEdgeEvenWhenItIsAnErrorEdge() {
        // Deliberate, documented divergence from the in-process rule (see nextNodeIdFirstEdge javadoc):
        // changing it would alter decisions on histories already recorded.
        List<WorkflowEdgeDto> edges = List.of(edge("err", "error"), edge("ok", null));
        assertEquals("err", WorkflowTraversalRules.nextNodeIdFirstEdge(edges, null));
        assertEquals("ok", WorkflowTraversalRules.nextNodeId(edges, null));
        assertNull(WorkflowTraversalRules.nextNodeIdFirstEdge(List.of(), null));
        assertNull(WorkflowTraversalRules.nextNodeId(List.of(), "x"));
    }

    @Test
    void branchSelectionIsSharedByBothEngines() {
        List<WorkflowEdgeDto> edges = List.of(edge("t", "true"), edge("f", "false"));
        assertEquals("f", WorkflowTraversalRules.nextNodeId(edges, "False"));
        assertEquals("f", WorkflowTraversalRules.nextNodeIdFirstEdge(edges, "False"));
        assertNull(WorkflowTraversalRules.nextNodeId(edges, "nope"));
        assertNull(WorkflowTraversalRules.nextNodeIdFirstEdge(edges, "nope"));
    }

    @Test
    void resolveItemsReportsWhyTheListIsEmpty() {
        WorkflowNodeDto n = node(Map.of());
        assertEquals(WorkflowTraversalRules.ItemsProblem.NONE, WorkflowTraversalRules.resolveItems(MAPPER, n, "[]").problem());
        assertEquals(WorkflowTraversalRules.ItemsProblem.NOT_A_LIST, WorkflowTraversalRules.resolveItems(MAPPER, n, "{}").problem());
        assertEquals(WorkflowTraversalRules.ItemsProblem.INVALID_JSON, WorkflowTraversalRules.resolveItems(MAPPER, n, "[").problem());
    }

    @Test
    void backoffCapIsPureAndJitterIsInjectable() {
        assertEquals(500, BackoffCalculator.capMillis(1));
        assertEquals(10_000, BackoffCalculator.capMillis(99));
        assertEquals(500, BackoffCalculator.capMillis(-1));
        // Upper bound passed to the random source is cap + 1, so the delay can equal the cap.
        assertEquals(1_000, BackoffCalculator.jitteredDelayMillis(2, bound -> bound - 1));
        assertEquals(0, BackoffCalculator.jitteredDelayMillis(2, bound -> 0));
    }

    @Test
    void retryPolicyDecisions() {
        WorkflowNodeDto agent = new WorkflowNodeDto("a", "agent", Map.of(), Map.of("config", Map.of("retryAttempts", 2)));
        WorkflowNodeDto output = new WorkflowNodeDto("o", "output", Map.of(), Map.of());
        assertEquals(3, RetryPolicy.totalAttempts(agent, 0, -1));
        assertEquals(1, RetryPolicy.totalAttempts(agent, 0, 1));
        assertEquals(4, RetryPolicy.totalAttempts(output, 3, -1));
        assertTrue(RetryPolicy.shouldRetry(agent, 1, 3, new RuntimeException("x")));
        assertFalse(RetryPolicy.shouldRetry(agent, 3, 3, new RuntimeException("x")));
        assertFalse(RetryPolicy.shouldRetry(output, 1, 3, new RuntimeException("x")));
        assertFalse(RetryPolicy.shouldRetry(agent, 1, 3,
            new com.agenticai.core.security.TenantLimitExceededException("quota")));
    }

    @Test
    void parallelRunnerReportsFirstFailureInBranchOrder() {
        ParallelBranchRunner runner = new ParallelBranchRunner(Runnable::run);
        WorkflowNodeDto a = new WorkflowNodeDto("a", "agent", Map.of(), Map.of());
        WorkflowNodeDto b = new WorkflowNodeDto("b", "agent", Map.of(), Map.of());
        ParallelBranchRunner.Joined joined = runner.run(List.of(a, b), node -> {
            throw new IllegalStateException("boom " + node.id());
        });
        assertSame(a, joined.failedBranch());
        assertTrue(joined.outcomes().isEmpty());
        assertThrows(RuntimeException.class, () -> { throw joined.failure(); });
    }

    @Test
    void fanOutRunnerDeclinesToStartItemsAfterCancellation() {
        FanOutRunner.Joined joined = new FanOutRunner().run("run", List.of("a", "b", "c"), 3, 2, () -> true,
            (item, scope) -> { throw new AssertionError("must not start"); });
        assertNull(joined.failure());
        assertEquals(3, joined.outcomes().size());
        assertTrue(joined.outcomes().stream().allMatch(FanOutRunner.ItemOutcome::cancelledBeforeStart));
    }
}
