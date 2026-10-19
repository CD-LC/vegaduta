package com.agenticai.core.workflow.guidance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowSendApprovalGuardTest {

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of("x", 80, "y", 160), Map.of("label", id, "config", config));
    }

    private static WorkflowNodeDto at(String id, String type, int x, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of("x", x, "y", 160), Map.of("label", id, "config", config));
    }

    private static WorkflowEdgeDto edge(String id, String from, String to, String handle) {
        return new WorkflowEdgeDto(id, from, to, handle, null);
    }

    private static WorkflowNodeDto byId(WorkflowGraphDto g, String id) {
        return g.nodes().stream().filter(n -> id.equals(n.id())).findFirst().orElseThrow();
    }

    private static WorkflowEdgeDto edgeById(WorkflowGraphDto g, String id) {
        return g.edges().stream().filter(e -> id.equals(e.id())).findFirst().orElseThrow();
    }

    private static List<String> sendIds(WorkflowGraphDto g) {
        return WorkflowSendApprovalGuard.ungatedSends(g).stream().map(WorkflowNodeDto::id).toList();
    }

    @Test
    void classifiesOutboundSends() {
        assertTrue(WorkflowSendApprovalGuard.isOutboundSend(node("o", "output", Map.of("channel", "whatsapp"))));
        assertTrue(WorkflowSendApprovalGuard.isOutboundSend(node("o", "output", Map.of("channel", "email"))));
        assertTrue(WorkflowSendApprovalGuard.isOutboundSend(node("o", "output", Map.of("channel", "telegram"))));
        assertTrue(WorkflowSendApprovalGuard.isOutboundSend(node("o", "output", Map.of("channel", "teams"))));
        assertFalse(WorkflowSendApprovalGuard.isOutboundSend(node("o", "output", Map.of("channel", "log"))));
        assertFalse(WorkflowSendApprovalGuard.isOutboundSend(node("o", "output", Map.of())));
        assertFalse(WorkflowSendApprovalGuard.isOutboundSend(node("o", "output", Map.of("channel", "google_drive"))));
        assertTrue(WorkflowSendApprovalGuard.isOutboundSend(node("b", "bulk_email", Map.of())));
        assertTrue(WorkflowSendApprovalGuard.isOutboundSend(node("h", "http", Map.of("method", "post"))));
        assertTrue(WorkflowSendApprovalGuard.isOutboundSend(node("h", "http", Map.of("method", "DELETE"))));
        assertFalse(WorkflowSendApprovalGuard.isOutboundSend(node("h", "http", Map.of("method", "GET"))));
        assertFalse(WorkflowSendApprovalGuard.isOutboundSend(node("h", "http", Map.of())), "blank method is GET");
        assertTrue(WorkflowSendApprovalGuard.isOutboundSend(node("t", "tool", Map.of("toolName", "send_email"))));
        assertFalse(WorkflowSendApprovalGuard.isOutboundSend(node("t", "tool", Map.of("toolName", "web_search"))));
        assertFalse(WorkflowSendApprovalGuard.isOutboundSend(node("a", "agent", Map.of())));
    }

    @Test
    void simulatableSetCoversEverySideEffectKnownFromConfig() {
        assertTrue(WorkflowSendApprovalGuard.isSimulatableSend(node("o", "output", Map.of("channel", "google_drive"))));
        assertTrue(WorkflowSendApprovalGuard.isSimulatableSend(node("o", "output", Map.of("channel", "whatsapp"))));
        assertTrue(WorkflowSendApprovalGuard.isSimulatableSend(node("b", "bulk_email", Map.of())));
        assertTrue(WorkflowSendApprovalGuard.isSimulatableSend(node("h", "http", Map.of("method", "PUT"))));
        assertFalse(WorkflowSendApprovalGuard.isSimulatableSend(node("h", "http", Map.of("method", "GET"))));
        assertFalse(WorkflowSendApprovalGuard.isSimulatableSend(node("o", "output", Map.of("channel", "file"))));
        assertTrue(WorkflowSendApprovalGuard.isSimulatableSend(node("t", "tool", Map.of("toolName", "send_email"))));
        assertFalse(WorkflowSendApprovalGuard.isSimulatableSend(node("t", "tool", Map.of("toolName", "web_search"))));
        assertTrue(WorkflowSendApprovalGuard.isSimulatableSend(node("w", "workflow", Map.of("targetWorkflowId", "x"))));
        assertTrue(WorkflowSendApprovalGuard.isSimulatableSend(node("c", "crm_lead", Map.of("action", "upsert"))));
        assertTrue(WorkflowSendApprovalGuard.isSimulatableSend(node("d", "device_control", Map.of())));
        assertTrue(WorkflowSendApprovalGuard.isSimulatableSend(node("o", "output", Map.of("channel", "event"))));
        assertFalse(WorkflowSendApprovalGuard.isSimulatableSend(node("o", "output", Map.of("channel", "log"))));
        assertFalse(WorkflowSendApprovalGuard.isSimulatableSend(node("a", "agent", Map.of())));
        // Everything the guard calls a send (bar the sub_flow container) is also simulated: one classification.
        for (WorkflowNodeDto send : List.of(node("o", "output", Map.of("channel", "teams")), node("b", "bulk_email", Map.of()),
            node("h", "http", Map.of("method", "DELETE")), node("t", "tool", Map.of("toolName", "send_sms")))) {
            assertTrue(WorkflowSendApprovalGuard.isOutboundSend(send));
            assertTrue(WorkflowSendApprovalGuard.isSimulatableSend(send));
        }
    }

    @Test
    void insertsOneApprovalOnTheIncomingEdgeAndKeepsEdgeIdAndHandle() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(at("t", "trigger", 80, Map.of()), at("c", "condition", 340, Map.of("contains", "x")),
                at("send", "output", 600, Map.of("channel", "whatsapp"))),
            List.of(edge("e1", "t", "c", null), edge("e2", "c", "send", "true")));

        WorkflowSendApprovalGuard.Result r = WorkflowSendApprovalGuard.enforce(g);

        assertTrue(r.changed());
        assertEquals(1, r.insertions().size());
        assertEquals("auto-approval-send", r.insertions().get(0).approvalNodeId());
        assertEquals("send", r.insertions().get(0).beforeNodeId());
        WorkflowNodeDto approval = byId(r.graph(), "auto-approval-send");
        assertEquals("approval", approval.type());
        assertEquals("Approve before sending", approval.label());
        assertEquals(Boolean.TRUE, approval.config().get("autoInserted"));
        assertEquals(470L, ((Number) approval.position().get("x")).longValue(), "midway between source and target");
        WorkflowEdgeDto rerouted = edgeById(r.graph(), "e2");
        assertEquals("c", rerouted.source());
        assertEquals("auto-approval-send", rerouted.target());
        assertEquals("true", rerouted.sourceHandle());
        assertTrue(r.graph().edges().stream().anyMatch(
            e -> "auto-approval-send".equals(e.source()) && "send".equals(e.target())));
        assertEquals(1, r.notes().size());
        assertTrue(r.notes().get(0).contains("send"), r.notes().get(0));
        assertTrue(sendIds(r.graph()).isEmpty());
    }

    @Test
    void isIdempotent() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("send", "bulk_email", Map.of())),
            List.of(edge("e1", "t", "send", null)));
        WorkflowSendApprovalGuard.Result first = WorkflowSendApprovalGuard.enforce(g);
        WorkflowSendApprovalGuard.Result second = WorkflowSendApprovalGuard.enforce(first.graph());
        assertEquals(3, first.graph().nodes().size());
        assertFalse(second.changed());
        assertSame(first.graph(), second.graph());
        assertTrue(second.notes().isEmpty());
    }

    @Test
    void alreadyGatedSendsAreLeftAlone() {
        WorkflowGraphDto viaApproval = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("ok", "approval", Map.of()),
                node("send", "output", Map.of("channel", "email"))),
            List.of(edge("e1", "t", "ok", null), edge("e2", "ok", "send", null)));
        assertSame(viaApproval, WorkflowSendApprovalGuard.enforce(viaApproval).graph());

        WorkflowGraphDto viaFlag = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()),
                node("send", "http", Map.of("method", "POST", "requiresApproval", true))),
            List.of(edge("e1", "t", "send", null)));
        assertFalse(WorkflowSendApprovalGuard.enforce(viaFlag).changed());

        WorkflowGraphDto viaHumanInput = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("form", "human_input", Map.of()),
                node("a", "agent", Map.of()), node("send", "output", Map.of("channel", "telegram"))),
            List.of(edge("e1", "t", "form", null), edge("e2", "form", "a", null), edge("e3", "a", "send", null)));
        assertFalse(WorkflowSendApprovalGuard.enforce(viaHumanInput).changed());
    }

    @Test
    void onlyTheUngatedPathIsRerouted() {
        // t -> ok(approval) -> send, and t -> a -> send: the second path skips the gate.
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("ok", "approval", Map.of()), node("a", "agent", Map.of()),
                node("send", "output", Map.of("channel", "email"))),
            List.of(edge("e1", "t", "ok", null), edge("e2", "ok", "send", null),
                edge("e3", "t", "a", null), edge("e4", "a", "send", null)));
        assertEquals(List.of("send"), sendIds(g));

        WorkflowGraphDto fixed = WorkflowSendApprovalGuard.enforce(g).graph();

        assertEquals("send", edgeById(fixed, "e2").target(), "the path that already had a gate is not gated twice");
        assertEquals("auto-approval-send", edgeById(fixed, "e4").target());
        assertTrue(sendIds(fixed).isEmpty());
    }

    @Test
    void loopBodySendIsGatedInFrontOfTheLoop() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("fetch", "http", Map.of("method", "GET")),
                node("each", "loop", Map.of()), node("send", "output", Map.of("channel", "whatsapp"))),
            List.of(edge("e1", "t", "fetch", null), edge("e2", "fetch", "each", null), edge("e3", "each", "send", null)));
        assertEquals(List.of("each"), WorkflowSendApprovalGuard.findings(g).get(0).insertBeforeNodeIds());

        WorkflowSendApprovalGuard.Result r = WorkflowSendApprovalGuard.enforce(g);

        assertEquals("each", r.insertions().get(0).beforeNodeId());
        assertEquals("send", r.insertions().get(0).sendNodeId());
        assertEquals("send", edgeById(r.graph(), "e3").target(), "the loop still repeats the send, not the approval");
        assertEquals("auto-approval-each", edgeById(r.graph(), "e2").target());
        assertTrue(sendIds(r.graph()).isEmpty());
        assertFalse(WorkflowSendApprovalGuard.enforce(r.graph()).changed());
    }

    @Test
    void subFlowWithASendInItsBodyIsGatedAsAWhole() {
        Map<String, Object> body = Map.of(
            "nodes", List.of(Map.of("id", "b1", "type", "output", "data", Map.of("config", Map.of("channel", "email")))),
            "edges", List.of());
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("sf", "sub_flow", Map.of("body", body))),
            List.of(edge("e1", "t", "sf", null)));
        assertEquals(List.of("sf"), sendIds(g));
        assertTrue(WorkflowSendApprovalGuard.enforce(g).changed());
    }

    @Test
    void unreachableSendsAndSafeGraphsAreUntouched() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("log", "output", Map.of("channel", "log")),
                node("island", "output", Map.of("channel", "email"))),
            List.of(edge("e1", "t", "log", null)));
        assertSame(g, WorkflowSendApprovalGuard.enforce(g).graph());
        assertTrue(WorkflowSendApprovalGuard.enforce(null).notes().isEmpty());
    }

    @Test
    void oneApprovalCoversEverySendBehindIt() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("wa", "output", Map.of("channel", "whatsapp")),
                node("mail", "output", Map.of("channel", "email"))),
            List.of(edge("e1", "t", "mail", null), edge("e2", "mail", "wa", null)));
        WorkflowSendApprovalGuard.Result r = WorkflowSendApprovalGuard.enforce(g);
        assertEquals(1, r.insertions().size());
        assertEquals("mail", r.insertions().get(0).beforeNodeId(), "the send nearest the trigger is gated first");
        assertTrue(sendIds(r.graph()).isEmpty());
    }

    @Test
    void sendsOnSeparateBranchesGetDistinctlyLabelledApprovals() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("c", "condition", Map.of("contains", "x")),
                node("mail", "output", Map.of("channel", "email")), node("wa", "output", Map.of("channel", "whatsapp"))),
            List.of(edge("e1", "t", "c", null), edge("e2", "c", "mail", "true"), edge("e3", "c", "wa", "false")));
        WorkflowSendApprovalGuard.Result r = WorkflowSendApprovalGuard.enforce(g);
        assertEquals(2, r.insertions().size());
        List<String> labels = r.graph().nodes().stream().filter(n -> "approval".equals(n.type()))
            .map(WorkflowNodeDto::label).toList();
        assertEquals(2, labels.stream().distinct().count(), labels.toString());
        assertTrue(labels.contains("Approve before sending"), labels.toString());
        assertTrue(sendIds(r.graph()).isEmpty());
    }
}
