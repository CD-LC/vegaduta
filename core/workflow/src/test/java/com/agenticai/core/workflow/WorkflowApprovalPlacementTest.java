package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Where a human-decision node may sit: the main path only. A halt inside a container body parks the
 * run with a resume envelope carrying the node id and its input but no iteration cursor - resume
 * would drop the remaining items (loop/fan-out/parallel) or fail outright (sub-flow bodies are not
 * in the main graph's node table).
 */
class WorkflowApprovalPlacementTest {

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("label", id, "config", config));
    }

    private static List<String> run(
            List<WorkflowNodeDto> nodes, Map<String, List<String>> adjacency) {
        Map<String, WorkflowNodeDto> byId = new LinkedHashMap<>();
        nodes.forEach(n -> byId.put(n.id(), n));
        List<String> errors = new ArrayList<>();
        WorkflowApprovalPlacement.validate(nodes, byId, adjacency, errors);
        return errors;
    }

    @Test
    void anApprovalGateOnTheMainPathIsAccepted() {
        List<WorkflowNodeDto> nodes =
                List.of(
                        node("t", "trigger", Map.of()),
                        node("a", "approval", Map.of()),
                        node("g", "agent", Map.of("requiresApproval", true)),
                        node("h", "human_input", Map.of()));
        assertTrue(
                run(nodes, Map.of("t", List.of("a"), "a", List.of("g"), "g", List.of("h")))
                        .isEmpty());
    }

    @Test
    void anApprovalNodeAsAContainerTargetIsRejected() {
        for (String container : List.of("loop", "fan_out", "parallel")) {
            List<WorkflowNodeDto> nodes =
                    List.of(node("c", container, Map.of()), node("a", "approval", Map.of()));
            List<String> errors = run(nodes, Map.of("c", List.of("a")));
            assertEquals(1, errors.size(), container);
            assertTrue(errors.get(0).contains("Approval node 'a'"), errors.get(0));
        }
    }

    @Test
    void aRequiresApprovalFlagAsAContainerTargetIsRejected() {
        for (String container : List.of("loop", "fan_out", "parallel")) {
            List<WorkflowNodeDto> nodes =
                    List.of(
                            node("c", container, Map.of()),
                            node("g", "agent", Map.of("requiresApproval", true)));
            List<String> errors = run(nodes, Map.of("c", List.of("g")));
            assertEquals(1, errors.size(), container);
            assertTrue(errors.get(0).contains("approval-gated node 'g'"), errors.get(0));
        }
    }

    @Test
    void aHumanInputAsAContainerTargetIsRejected() {
        List<WorkflowNodeDto> nodes =
                List.of(node("c", "loop", Map.of()), node("h", "human_input", Map.of()));
        List<String> errors = run(nodes, Map.of("c", List.of("h")));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Human Input node 'h'"), errors.get(0));
    }

    @Test
    void aGatedNodeDownstreamOfTheBodyIsNotTheBody() {
        // Loop -> body agent -> approval: the approval sits on the post-loop main path
        // (the loop's continuation resolves off the body node's own edges), so it stays legal.
        List<WorkflowNodeDto> nodes =
                List.of(
                        node("c", "loop", Map.of()),
                        node("b", "agent", Map.of()),
                        node("a", "approval", Map.of()));
        assertTrue(run(nodes, Map.of("c", List.of("b"), "b", List.of("a"))).isEmpty());
    }

    @Test
    void anApprovalInsideASubFlowBodyIsRejected() {
        Map<String, Object> body =
                Map.of(
                        "nodes",
                        List.of(
                                Map.of(
                                        "id",
                                        "x",
                                        "type",
                                        "agent",
                                        "data",
                                        Map.of("label", "Fetch")),
                                Map.of(
                                        "id",
                                        "y",
                                        "type",
                                        "approval",
                                        "data",
                                        Map.of("label", "Sign off"))));
        List<String> errors = run(List.of(node("s", "sub_flow", Map.of("body", body))), Map.of());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Sub-Flow node 's'"), errors.get(0));
        assertTrue(errors.get(0).contains("Sign off"), errors.get(0));
    }

    @Test
    void aRequiresApprovalInsideANestedSubFlowBodyIsRejected() {
        Map<String, Object> inner =
                Map.of(
                        "nodes",
                        List.of(
                                Map.of(
                                        "id",
                                        "n",
                                        "type",
                                        "tool",
                                        "data",
                                        Map.of(
                                                "label",
                                                "Pay",
                                                "config",
                                                Map.of("requiresApproval", true)))));
        Map<String, Object> outer =
                Map.of(
                        "nodes",
                        List.of(
                                Map.of(
                                        "id",
                                        "m",
                                        "type",
                                        "sub_flow",
                                        "data",
                                        Map.of(
                                                "label",
                                                "Inner",
                                                "config",
                                                Map.of("body", inner)))));
        List<String> errors = run(List.of(node("s", "sub_flow", Map.of("body", outer))), Map.of());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Pay"), errors.get(0));
    }

    @Test
    void aBranchFlaggedFalseIsAccepted() {
        List<WorkflowNodeDto> nodes =
                List.of(
                        node("c", "parallel", Map.of()),
                        node("g", "agent", Map.of("requiresApproval", false)));
        assertTrue(run(nodes, Map.of("c", List.of("g"))).isEmpty());
    }
}
