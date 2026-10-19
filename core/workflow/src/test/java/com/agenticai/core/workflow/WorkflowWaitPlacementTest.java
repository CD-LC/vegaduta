package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Where a Wait node may sit: the main path only, and never on the Temporal engine. */
class WorkflowWaitPlacementTest {

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("label", id, "config", config));
    }

    private static List<String> run(List<WorkflowNodeDto> nodes, Map<String, List<String>> adjacency, boolean temporal) {
        Map<String, WorkflowNodeDto> byId = new LinkedHashMap<>();
        nodes.forEach(n -> byId.put(n.id(), n));
        List<String> errors = new ArrayList<>();
        WorkflowWaitPlacement.validate(nodes, byId, adjacency, temporal, errors);
        return errors;
    }

    @Test
    void aWaitOnTheMainPathIsAccepted() {
        List<WorkflowNodeDto> nodes = List.of(node("t", "trigger", Map.of()), node("w", "wait", Map.of()),
            node("a", "agent", Map.of()));
        assertTrue(run(nodes, Map.of("t", List.of("w"), "w", List.of("a")), false).isEmpty());
    }

    @Test
    void aWaitAsALoopFanOutOrParallelTargetIsRejected() {
        for (String container : List.of("loop", "fan_out", "parallel")) {
            List<WorkflowNodeDto> nodes = List.of(node("c", container, Map.of()), node("w", "wait", Map.of()));
            List<String> errors = run(nodes, Map.of("c", List.of("w")), false);
            assertEquals(1, errors.size(), container);
            assertTrue(errors.get(0).contains("Wait node 'w'"), errors.get(0));
        }
    }

    @Test
    void aWaitInsideASubFlowBodyIsRejected() {
        Map<String, Object> body = Map.of("nodes", List.of(
            Map.of("id", "x", "type", "agent"), Map.of("id", "y", "type", "wait")));
        List<String> errors = run(List.of(node("s", "sub_flow", Map.of("body", body))), Map.of(), false);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Sub-Flow node 's'"), errors.get(0));
    }

    @Test
    void aWaitIsRejectedOnTheTemporalEngine() {
        List<String> errors = run(List.of(node("w", "wait", Map.of())), Map.of(), true);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Temporal"), errors.get(0));
    }
}
