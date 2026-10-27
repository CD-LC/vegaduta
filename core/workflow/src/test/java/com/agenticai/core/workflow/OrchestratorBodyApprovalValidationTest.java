package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Approval / human-input steps cannot be the body of a loop, fan_out or sub_flow (they cannot resume). */
class OrchestratorBodyApprovalValidationTest extends OrchestratorServiceTestBase {

    private WorkflowGraphDto containerGraph(String containerType, String bodyType, Map<String, Object> bodyConfig) {
        return new WorkflowGraphDto(
            List.of(trigger("t1"), node("c1", containerType, Map.of()), node("b1", bodyType, bodyConfig)),
            List.of(edge("e1", "t1", "c1"), edge("e2", "c1", "b1")));
    }

    @Test
    void loopBodyWithRequiresApprovalIsRejected() {
        List<String> errors = errorsFrom(containerGraph("loop", "output", Map.of("channel", "log", "requiresApproval", true)));
        assertTrue(errors.stream().anyMatch(e -> e.contains("Loop node 'c1'") && e.contains("requiresApproval=true")), errors.toString());
    }

    @Test
    void loopBodyThatIsAnApprovalNodeIsRejected() {
        List<String> errors = errorsFrom(containerGraph("loop", "approval", Map.of()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("Loop node 'c1'") && e.contains("type approval")), errors.toString());
    }

    @Test
    void fanOutBodyWithRequiresApprovalOrHumanInputIsRejected() {
        List<String> gated = errorsFrom(containerGraph("fan_out", "output", Map.of("channel", "log", "requiresApproval", "true")));
        assertTrue(gated.stream().anyMatch(e -> e.contains("Fan-out node 'c1'")), gated.toString());
        List<String> human = errorsFrom(containerGraph("fan_out", "human_input", Map.of()));
        assertTrue(human.stream().anyMatch(e -> e.contains("Fan-out node 'c1'") && e.contains("human_input")), human.toString());
    }

    @Test
    void loopBodyWithoutApprovalIsStillValid() {
        assertDoesNotThrow(() -> orchestrator.validate(containerGraph("loop", "output", Map.of("channel", "log"))));
    }

    @Test
    void subFlowBodyNodeWithApprovalIsRejected() {
        Map<String, Object> bodyNode = Map.of("id", "x", "type", "output",
            "data", Map.of("label", "Send", "config", Map.of("channel", "log", "requiresApproval", true)));
        Map<String, Object> approvalNode = Map.of("id", "y", "type", "approval", "data", Map.of("label", "OK?"));
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("s1", "sub_flow",
                Map.of("body", Map.of("nodes", List.of(bodyNode, approvalNode), "edges", List.of())))),
            List.of(edge("e1", "t1", "s1")));
        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Sub-flow node 's1'") && e.contains("'Send'")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Sub-flow node 's1'") && e.contains("type approval")), errors.toString());
    }

    @Test
    void subFlowBodyWithoutApprovalIsValid() {
        Map<String, Object> bodyNode = Map.of("id", "x", "type", "output",
            "data", Map.of("config", Map.of("channel", "log")));
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("s1", "sub_flow",
                Map.of("body", Map.of("nodes", List.of(bodyNode), "edges", List.of())))),
            List.of(edge("e1", "t1", "s1")));
        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }
}
