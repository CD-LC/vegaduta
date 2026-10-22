package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Characterisation of the traversal/retry rules as seen through {@link WorkflowGraphSupport},
 * written BEFORE they were moved into {@link WorkflowTraversalRules} / {@link RetryPolicy} / {@link
 * BackoffCalculator}. It must pass unchanged before and after that move.
 */
class WorkflowGraphSupportCharacterisationTest {

    private final WorkflowGraphSupport support = new WorkflowGraphSupport(new ObjectMapper());

    private static WorkflowNodeDto node(Map<String, Object> config) {
        return new WorkflowNodeDto("n", "loop", Map.of(), Map.of("config", config, "label", "L"));
    }

    private static WorkflowEdgeDto edge(String target, String handle) {
        return new WorkflowEdgeDto("e-" + target, "n", target, handle, null);
    }

    @Test
    void nextNodeIdWithoutBranchSkipsErrorEdgesAndTakesFirst() {
        var n = node(Map.of());
        assertNull(support.nextNodeId(n, List.of(), null));
        assertEquals(
                "b",
                support.nextNodeId(
                        n, List.of(edge("a", "error"), edge("b", null), edge("c", null)), null));
        assertNull(support.nextNodeId(n, List.of(edge("a", "error")), null));
    }

    @Test
    void nextNodeIdWithBranchMatchesHandleCaseInsensitively() {
        var n = node(Map.of());
        List<WorkflowEdgeDto> edges = List.of(edge("t", "true"), edge("f", "false"));
        assertEquals("f", support.nextNodeId(n, edges, "FALSE"));
        assertNull(support.nextNodeId(n, edges, "other"));
    }

    @Test
    void loopAndFanOutClamps() {
        assertEquals(25, support.loopMaxIterations(node(Map.of())));
        assertEquals(1, support.loopMaxIterations(node(Map.of("maxIterations", -4))));
        assertEquals(100, support.loopMaxIterations(node(Map.of("maxIterations", 5000))));
        assertEquals(7, support.loopMaxIterations(node(Map.of("maxIterations", 7))));
        assertEquals(25, support.loopMaxIterations(node(Map.of("maxIterations", "7"))));
        assertEquals(25, support.fanOutMaxItems(node(Map.of())));
        assertEquals(100, support.fanOutMaxItems(node(Map.of("maxItems", 1000))));
        assertEquals(1, support.fanOutMaxItems(node(Map.of("maxItems", 0))));
        assertEquals(5, support.fanOutConcurrency(node(Map.of())));
        assertEquals(10, support.fanOutConcurrency(node(Map.of("maxConcurrency", 99))));
        assertEquals(1, support.fanOutConcurrency(node(Map.of("maxConcurrency", 0))));
    }

    @Test
    void resolveLoopItemsShapes() {
        var n = node(Map.of());
        assertEquals(
                List.of("a", "{\"k\":1}", "2"), support.resolveLoopItems(n, "[\"a\",{\"k\":1},2]"));
        assertEquals(List.of(), support.resolveLoopItems(n, null));
        assertEquals(List.of(), support.resolveLoopItems(n, "  "));
        assertEquals(List.of(), support.resolveLoopItems(n, "{\"x\":1}"));
        assertEquals(List.of(), support.resolveLoopItems(n, "not json"));
        var field = node(Map.of("itemsField", " rows "));
        assertEquals(List.of("1", "2"), support.resolveLoopItems(field, "{\"rows\":[1,2]}"));
        assertEquals(List.of(), support.resolveLoopItems(field, "{\"other\":[1]}"));
        assertEquals("[\"a\",\"b\"]", support.writeJsonArray(List.of("a", "b")));
    }

    @Test
    void retryAttemptsPolicy() {
        assertEquals(3, WorkflowGraphSupport.nodeMaxAttempts(node(Map.of()), 3));
        assertEquals(0, WorkflowGraphSupport.nodeMaxAttempts(node(Map.of()), -2));
        assertEquals(5, WorkflowGraphSupport.nodeMaxAttempts(node(Map.of("retryAttempts", 99)), 1));
        assertEquals(0, WorkflowGraphSupport.nodeMaxAttempts(node(Map.of("retryAttempts", -1)), 1));
        assertEquals(
                2, WorkflowGraphSupport.nodeMaxAttempts(node(Map.of("retryAttempts", " 2 ")), 1));
        assertEquals(
                4, WorkflowGraphSupport.nodeMaxAttempts(node(Map.of("retryAttempts", "x")), 4));
    }
}
