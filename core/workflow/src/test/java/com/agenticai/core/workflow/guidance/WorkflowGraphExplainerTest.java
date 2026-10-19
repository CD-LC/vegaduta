package com.agenticai.core.workflow.guidance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.guidance.WorkflowGraphExplainer.ExplainedStep;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowGraphExplainerTest {

    private static WorkflowNodeDto node(String id, String type, String label, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("label", label, "config", config));
    }

    private static WorkflowEdgeDto edge(String id, String from, String to, String handle) {
        return new WorkflowEdgeDto(id, from, to, handle, null);
    }

    private static ExplainedStep step(List<ExplainedStep> steps, String nodeId) {
        return steps.stream().filter(s -> nodeId.equals(s.nodeId())).findFirst().orElseThrow();
    }

    @Test
    void walksFromTheTriggerInEdgeOrderAndSkipsUnreachableNodes() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("island", "code", "Island", Map.of("operation", "uppercase")),
                node("out", "output", "Send", Map.of("channel", "whatsapp")),
                node("ok", "approval", "Approve", Map.of()),
                node("fetch", "http", "Unpaid invoices", Map.of("method", "GET", "url", "https://api.example.com/invoices")),
                node("t", "trigger", "Start", Map.of("channel", "WEBHOOK"))),
            List.of(edge("e1", "t", "fetch", null), edge("e2", "fetch", "ok", null), edge("e3", "ok", "out", null)));

        List<ExplainedStep> steps = WorkflowGraphExplainer.explain(g);

        assertEquals(List.of("t", "fetch", "ok", "out"), steps.stream().map(ExplainedStep::nodeId).toList());
        assertEquals("trigger", steps.get(0).type());
        assertEquals("Start", steps.get(0).label());
        assertTrue(steps.get(0).sentence().contains("schedule"), steps.get(0).sentence());
        assertEquals("Fetches data from api.example.com (GET request).", steps.get(1).sentence());
        assertTrue(steps.get(2).sentence().contains("pauses for your approval"), steps.get(2).sentence());
        assertEquals("Sends the result as a WhatsApp message to the person who messaged.", steps.get(3).sentence());
    }

    @Test
    void conditionNamesBothBranches() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", "Start", Map.of()),
                node("c", "condition", "Refund?", Map.of("contains", "refund")),
                node("yes", "approval", "Manager sign-off", Map.of()),
                node("no", "output", "Log it", Map.of("channel", "log"))),
            List.of(edge("e1", "t", "c", null), edge("e2", "c", "yes", "true"), edge("e3", "c", "no", "false")));

        List<ExplainedStep> steps = WorkflowGraphExplainer.explain(g);

        assertEquals("Checks whether the previous result mentions \"refund\": if yes it continues to "
            + "\"Manager sign-off\", otherwise to \"Log it\".", step(steps, "c").sentence());
        assertEquals(List.of("t", "c", "yes", "no"), steps.stream().map(ExplainedStep::nodeId).toList());
    }

    @Test
    void expressionConditionAndSupervisorRoutes() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", "Start", Map.of("channel", "WHATSAPP")),
                node("c", "condition", "Big order", Map.of("mode", "expression", "expression", "$json.total > 1000")),
                node("s", "supervisor", "Route", Map.of("routes", List.of(
                    Map.of("handle", "route-1", "label", "Sales"), Map.of("handle", "route-2", "label", "Support")))),
                node("sales", "agent", "Sales agent", Map.of()),
                node("support", "agent", "Support agent", Map.of())),
            List.of(edge("e1", "t", "c", null), edge("e2", "c", "s", "true"),
                edge("e3", "s", "sales", "route-1"), edge("e4", "s", "support", "route-2")));

        List<ExplainedStep> steps = WorkflowGraphExplainer.explain(g);

        assertEquals("Starts when a WhatsApp message arrives.", step(steps, "t").sentence());
        assertEquals("Checks whether $json.total > 1000: if yes it continues to \"Route\", otherwise this path stops.",
            step(steps, "c").sentence());
        assertEquals("An AI agent picks one path: \"Sales\" (goes to \"Sales agent\"), \"Support\" (goes to "
            + "\"Support agent\").", step(steps, "s").sentence());
    }

    @Test
    void sendsDescribeTheirDestinationWithoutQuotingExpressions() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", "Start", Map.of()),
                node("each", "loop", "Each invoice", Map.of()),
                node("wa", "output", "Remind", Map.of("channel", "whatsapp", "whatsappTo", "{{ $item.phone }}")),
                node("mail", "output", "Email", Map.of("channel", "email", "emailTo", "ops@example.com")),
                node("post", "http", "Post", Map.of("method", "POST", "url", "https://hooks.example.com/{{id}}")),
                node("bulk", "bulk_email", "Newsletter", Map.of("subject", "Hello")),
                node("crm", "crm_lead", "Lead", Map.of("action", "stage"))),
            List.of(edge("e1", "t", "each", null), edge("e2", "each", "wa", null), edge("e3", "wa", "mail", null),
                edge("e4", "mail", "post", null), edge("e5", "post", "bulk", null), edge("e6", "bulk", "crm", null)));

        List<ExplainedStep> steps = WorkflowGraphExplainer.explain(g);

        assertTrue(step(steps, "each").sentence().contains("\"Remind\""), step(steps, "each").sentence());
        assertEquals("Sends the result as a WhatsApp message to an address taken from the data.", step(steps, "wa").sentence());
        assertFalse(step(steps, "wa").sentence().contains("{{"));
        assertEquals("Sends the result by email to ops@example.com.", step(steps, "mail").sentence());
        assertEquals("Sends data to hooks.example.com (POST request).", step(steps, "post").sentence());
        assertEquals("Sends a personalised email to each recipient with the subject \"Hello\".", step(steps, "bulk").sentence());
        assertEquals("Moves the lead to another stage in your CRM.", step(steps, "crm").sentence());
    }

    @Test
    void sharedDownstreamNodesAppearOnceAndEmptyGraphsExplainNothing() {
        WorkflowGraphDto diamond = new WorkflowGraphDto(
            List.of(node("t", "trigger", "Start", Map.of()), node("p", "parallel", "Both", Map.of()),
                node("a", "agent", "A", Map.of()), node("b", "agent", "B", Map.of()),
                node("o", "output", "Done", Map.of())),
            List.of(edge("e1", "t", "p", null), edge("e2", "p", "a", null), edge("e3", "p", "b", null),
                edge("e4", "a", "o", null), edge("e5", "b", "o", null)));
        List<ExplainedStep> steps = WorkflowGraphExplainer.explain(diamond);
        assertEquals(5, steps.size());
        assertEquals("Runs 2 branches at the same time on the same input.", step(steps, "p").sentence());
        assertEquals("Records the result in the run log.", step(steps, "o").sentence());

        assertTrue(WorkflowGraphExplainer.explain(null).isEmpty());
        assertTrue(WorkflowGraphExplainer.explain(new WorkflowGraphDto(List.of(), List.of())).isEmpty());
    }

    @Test
    void isDeterministic() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", "Start", Map.of()), node("a", "agent", "Writer", Map.of())),
            List.of(edge("e1", "t", "a", null)));
        assertEquals(WorkflowGraphExplainer.explain(g), WorkflowGraphExplainer.explain(g));
        assertEquals("The AI agent \"Writer\" works on the result of the previous step.",
            WorkflowGraphExplainer.explain(g).get(1).sentence());
    }
}
