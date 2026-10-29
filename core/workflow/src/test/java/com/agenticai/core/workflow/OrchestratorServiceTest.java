package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link OrchestratorService} is, in its own words, "the governance layer the user asked for
 * directly... which does the governance and ensure nothing goes wrong." Every workflow
 * save/activate/run passes through {@link OrchestratorService#validate}, and every sensitive node
 * passes through {@link OrchestratorService#authorizeSensitiveNode} - a bug in either is either a
 * false-negative (a broken graph gets marked runnable and fails confusingly mid-execution) or a
 * false-positive (a legitimate graph is rejected). Had zero test coverage before this suite despite
 * being the single most safety-critical class in the workflow engine.
 *
 * <p>This class covers graph shape (triggers, ids, edges, cycles, loop / parallel / fan_out arity);
 * node config rules are in {@link OrchestratorNodeConfigValidationTest}, the approval gate in
 * {@link OrchestratorApprovalGateTest}.
 */
class OrchestratorServiceTest extends OrchestratorServiceTestBase {

    // --- basic graph shape ----------------------------------------------------

    @Test
    void emptyGraphIsRejected() {
        List<String> errors = errorsFrom(new WorkflowGraphDto(List.of(), List.of()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("no nodes")));
    }

    @Test
    void graphWithNoTriggerNodeIsRejected() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(node("n1", "output", Map.of("channel", "log"))), List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("at least one Trigger node")));
    }

    // Per-node workflow triggers - "each trigger node in a workflow's graph
    // can be independently bound to its own inbound channel" replaces the
    // old "exactly one Trigger node total" model, so multiple Trigger nodes
    // are now structurally valid as long as they don't collide on the same
    // non-WEBHOOK channel (WEBHOOK is exempt - see validate()'s javadoc).
    @Test
    void graphWithMultipleWebhookTriggerNodesIsValid() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(List.of(trigger("t1"), trigger("t2")), List.of());

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    @Test
    void graphWithMultipleTriggerNodesOnTheSameNonWebhookChannelIsRejected() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(trigger("t1", "WHATSAPP"), trigger("t2", "WHATSAPP")), List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(
                errors.stream()
                        .anyMatch(
                                e ->
                                        e.contains(
                                                "Multiple trigger nodes are bound to channel WHATSAPP")));
    }

    @Test
    void graphWithMultipleTriggerNodesOnDifferentNonWebhookChannelsIsValid() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(trigger("t1", "WHATSAPP"), trigger("t2", "SMS")), List.of());

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    // 2026-08-09 deadlock fix regression coverage: a Trigger node's channel
    // is null until an author explicitly picks one (see nodeDefs.ts's
    // defaultConfig javadoc - "null falls back to WEBHOOK"), but
    // WorkflowService#setTriggerChannel requires the workflow to already be
    // ACTIVE for every channel except WEBHOOK. Rejecting activation for a
    // missing channel made a brand-new trigger node impossible to ever
    // configure: it couldn't activate without a channel, and couldn't get a
    // non-WEBHOOK channel without activating first. A missing channel must
    // validate the same as an explicit WEBHOOK one - see
    // graphWithMultipleWebhookTriggerNodesIsValid right above for the
    // explicit-WEBHOOK sibling of this exact case.
    @Test
    void triggerNodeWithNoChannelConfiguredDefaultsToWebhookAndIsAccepted() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(List.of(node("t1", "trigger", Map.of())), List.of());

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    @Test
    void triggerNodeWithInvalidChannelIsRejected() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(node("t1", "trigger", Map.of("channel", "carrier_pigeon"))),
                        List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("invalid channel value")));
    }

    @Test
    void singleTriggerNodeAloneIsValid() {
        WorkflowGraphDto graph = new WorkflowGraphDto(List.of(trigger("t1")), List.of());

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    @Test
    void duplicateNodeIdsAreRejected() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(trigger("dup"), node("dup", "output", Map.of("channel", "log"))),
                        List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate node id")));
    }

    @Test
    void blankNodeIdIsRejected() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(trigger("t1"), node("", "output", Map.of("channel", "log"))),
                        List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("must have an id")));
    }

    @Test
    void unrecognizedNodeTypeIsRejected() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(trigger("t1"), node("n2", "teleporter", Map.of())), List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("unrecognized type")));
    }

    @Test
    void edgeReferencingNonexistentNodeIsRejected() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(trigger("t1")), List.of(edge("e1", "t1", "does_not_exist")));

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("doesn't exist in this graph")));
    }

    @Test
    void allErrorsAreCollectedNotJustTheFirst() {
        // Two independent problems - both must be reported in one pass, not
        // just the first one found, so the canvas can flag every offending
        // node in one round trip (this class's own stated purpose).
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                trigger("t1", "WHATSAPP"),
                                trigger("t2", "WHATSAPP"),
                                node("bad", "teleporter", Map.of())),
                        List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.size() >= 2);
        assertTrue(
                errors.stream()
                        .anyMatch(
                                e ->
                                        e.contains(
                                                "Multiple trigger nodes are bound to channel WHATSAPP")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("unrecognized type")));
    }

    // --- cycle detection ---------------------------------------------------

    @Test
    void cyclicGraphIsRejected() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                trigger("t1"),
                                node("a", "output", Map.of("channel", "log")),
                                node("b", "output", Map.of("channel", "log"))),
                        List.of(edge("e1", "t1", "a"), edge("e2", "a", "b"), edge("e3", "b", "a")));

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("cycle")));
    }

    @Test
    void acyclicLinearGraphIsValid() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                trigger("t1"),
                                node("a", "output", Map.of("channel", "log")),
                                node("b", "output", Map.of("channel", "log"))),
                        List.of(edge("e1", "t1", "a"), edge("e2", "a", "b")));

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    // --- loop node -------------------------------------------------------------

    @Test
    void loopNodeWithNoOutgoingEdgeIsRejected() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(trigger("t1"), node("loop1", "loop", Map.of())),
                        List.of(edge("e1", "t1", "loop1")));

        List<String> errors = errorsFrom(graph);
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("needs exactly one outgoing connection")));
    }

    @Test
    void loopNodeWithMultipleOutgoingEdgesIsRejected() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                trigger("t1"),
                                node("loop1", "loop", Map.of()),
                                node("a", "output", Map.of("channel", "log")),
                                node("b", "output", Map.of("channel", "log"))),
                        List.of(
                                edge("e1", "t1", "loop1"),
                                edge("e2", "loop1", "a"),
                                edge("e3", "loop1", "b")));

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("more than one outgoing connection")));
    }

    @Test
    void loopNodeWithExactlyOneOutgoingEdgeIsValid() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                trigger("t1"),
                                node("loop1", "loop", Map.of()),
                                node("a", "output", Map.of("channel", "log"))),
                        List.of(edge("e1", "t1", "loop1"), edge("e2", "loop1", "a")));

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    // --- parallel node (Master Roadmap 2026 item #12, §E) ------------------------

    @Test
    void parallelNodeWithNoOutgoingEdgeIsRejected() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(trigger("t1"), node("p1", "parallel", Map.of())),
                        List.of(edge("e1", "t1", "p1")));

        List<String> errors = errorsFrom(graph);
        assertTrue(
                errors.stream()
                        .anyMatch(e -> e.contains("needs at least one outgoing connection")));
    }

    @Test
    void parallelNodeWithBranchCountWithinTheCapIsValid() {
        List<WorkflowNodeDto> nodes =
                new java.util.ArrayList<>(List.of(trigger("t1"), node("p1", "parallel", Map.of())));
        List<WorkflowEdgeDto> edges = new java.util.ArrayList<>(List.of(edge("e1", "t1", "p1")));
        for (int i = 0; i < WorkflowExecutionEngine.MAX_PARALLEL_BRANCHES; i++) {
            nodes.add(node("b" + i, "output", Map.of("channel", "log")));
            edges.add(edge("eb" + i, "p1", "b" + i));
        }
        WorkflowGraphDto graph = new WorkflowGraphDto(nodes, edges);

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    @Test
    void parallelNodeWithMoreThanMaxBranchesIsRejected() {
        // Regression test: this same over-cap graph used to pass validate() clean and
        // only fail once a run actually reached WorkflowExecutionEngine's runtime check -
        // i.e. it saved and activated successfully, then failed on every subsequent run.
        List<WorkflowNodeDto> nodes =
                new java.util.ArrayList<>(List.of(trigger("t1"), node("p1", "parallel", Map.of())));
        List<WorkflowEdgeDto> edges = new java.util.ArrayList<>(List.of(edge("e1", "t1", "p1")));
        int tooMany = WorkflowExecutionEngine.MAX_PARALLEL_BRANCHES + 1;
        for (int i = 0; i < tooMany; i++) {
            nodes.add(node("b" + i, "output", Map.of("channel", "log")));
            edges.add(edge("eb" + i, "p1", "b" + i));
        }
        WorkflowGraphDto graph = new WorkflowGraphDto(nodes, edges);

        List<String> errors = errorsFrom(graph);
        assertTrue(
                errors.stream()
                        .anyMatch(
                                e ->
                                        e.contains(tooMany + " branches")
                                                && e.contains(
                                                        "more than the "
                                                                + WorkflowExecutionEngine
                                                                        .MAX_PARALLEL_BRANCHES)));
    }

    // --- marketing content engine shape (brief -> parallel writers -> approval) --
    // Structural validation for the graph MarketingContentEngineWorkflowTest
    // (core/workflow module) exercises at the engine level, and V220's seeded
    // "Marketing Content Engine" community template mirrors: trigger -> agent
    // -> parallel(3 branches) -> each branch -> approval -> output, the same
    // 3-edges-converge-on-one-node shape V164's "Parallel Research Aggregator"
    // template already uses. Confirms this shape saves/activates cleanly, not
    // just that it happens to execute (see parallelNodeWithMoreThanMaxBranchesIsRejected's
    // own regression-test comment for why that distinction matters).

    @Test
    void supervisorBriefParallelWritersApprovalGraphIsValid() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                trigger("t1"),
                                node(
                                        "brief",
                                        "agent",
                                        Map.of("agentId", EXISTING_AGENT_ID.toString())),
                                node("fanout", "parallel", Map.of()),
                                node(
                                        "long",
                                        "agent",
                                        Map.of("agentId", EXISTING_AGENT_ID.toString())),
                                node(
                                        "short",
                                        "agent",
                                        Map.of("agentId", EXISTING_AGENT_ID.toString())),
                                node(
                                        "visual",
                                        "agent",
                                        Map.of("agentId", EXISTING_AGENT_ID.toString())),
                                node("gate", "approval", Map.of()),
                                node("out", "output", Map.of("channel", "log"))),
                        List.of(
                                edge("e1", "t1", "brief"),
                                edge("e2", "brief", "fanout"),
                                edge("e3", "fanout", "long"),
                                edge("e4", "fanout", "short"),
                                edge("e5", "fanout", "visual"),
                                edge("e6", "long", "gate"),
                                edge("e7", "short", "gate"),
                                edge("e8", "visual", "gate"),
                                edge("e9", "gate", "out")));

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    // --- fan_out node (Dynamic Fan-Out) ------------------------------------------
    // Mirrors the loop-node shape tests above deliberately: fan_out adopts
    // LOOP's exactly-one-outgoing-edge graph shape, not Parallel's N-edges
    // shape - see the validator's own comment for why that is load-bearing.

    @Test
    void fanOutNodeWithNoOutgoingEdgeIsRejected() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(trigger("t1"), node("f1", "fan_out", Map.of())),
                        List.of(edge("e1", "t1", "f1")));

        List<String> errors = errorsFrom(graph);
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("needs exactly one outgoing connection")));
    }

    @Test
    void fanOutNodeWithMultipleOutgoingEdgesIsRejected() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                trigger("t1"),
                                node("f1", "fan_out", Map.of()),
                                node("a", "output", Map.of("channel", "log")),
                                node("b", "output", Map.of("channel", "log"))),
                        List.of(
                                edge("e1", "t1", "f1"),
                                edge("e2", "f1", "a"),
                                edge("e3", "f1", "b")));

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("more than one outgoing connection")));
    }

    @Test
    void fanOutNodeWithExactlyOneOutgoingEdgeIsValid() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                trigger("t1"),
                                node("f1", "fan_out", Map.of()),
                                node("a", "output", Map.of("channel", "log"))),
                        List.of(edge("e1", "t1", "f1"), edge("e2", "f1", "a")));

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    @Test
    void fanOutNumericConfigIsNotValidatedAtSaveTime() {
        // Deliberate: maxItems/maxConcurrency are clamped by both engines at
        // run time exactly like loop's maxIterations (see validateNodeConfig's
        // default-case comment) - an out-of-range value degrades safely
        // instead of blocking a save.
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                trigger("t1"),
                                node(
                                        "f1",
                                        "fan_out",
                                        Map.of(
                                                "itemsField",
                                                "rows",
                                                "maxItems",
                                                9999,
                                                "maxConcurrency",
                                                -3)),
                                node("a", "output", Map.of("channel", "log"))),
                        List.of(edge("e1", "t1", "f1"), edge("e2", "f1", "a")));

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }
}
