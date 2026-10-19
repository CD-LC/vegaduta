package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link OrchestratorService#authorizeSensitiveNode}: the approval gate call and its resume envelope. */
class OrchestratorApprovalGateTest extends OrchestratorServiceTestBase {

    // --- authorizeSensitiveNode ------------------------------------------------

    @Test
    void authorizeSensitiveNodeDelegatesWithActionTypeDerivedFromNodeType() {
        when(approvalGateService.evaluate(any(), any(), any(), any())).thenReturn(ApprovalEvaluation.autoApproved());
        WorkflowNodeDto approvalNode = node("n1", "approval", Map.of());

        orchestrator.authorizeSensitiveNode("My Workflow", "run-123", approvalNode, "summary text", "user-1");

        verify(approvalGateService, times(1)).evaluate(
            eq("workflow.node.approval"), eq("summary text"), any(), eq("user-1"));
    }

    @Test
    void authorizeSensitiveNodePayloadIncludesWorkflowRunAndNodeIds() {
        when(approvalGateService.evaluate(any(), any(), any(), any())).thenReturn(ApprovalEvaluation.autoApproved());
        WorkflowNodeDto approvalNode = node("node-42", "approval", Map.of("adminOnly", true));

        orchestrator.authorizeSensitiveNode("My Workflow", "run-abc", approvalNode, "summary", "user-2");

        org.mockito.ArgumentCaptor<String> payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(approvalGateService).evaluate(any(), any(), payloadCaptor.capture(), any());
        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"workflowName\":\"My Workflow\""));
        assertTrue(payload.contains("\"runId\":\"run-abc\""));
        assertTrue(payload.contains("\"nodeId\":\"node-42\""));
        assertTrue(payload.contains("\"adminOnly\":true"));
    }

    @Test
    void authorizeSensitiveNodeReturnsWhateverApprovalGateServiceDecides() {
        UUID pendingId = UUID.randomUUID();
        when(approvalGateService.evaluate(any(), any(), any(), any())).thenReturn(ApprovalEvaluation.pending(pendingId));
        WorkflowNodeDto approvalNode = node("n1", "approval", Map.of());

        ApprovalEvaluation result = orchestrator.authorizeSensitiveNode("wf", "run", approvalNode, "s", "u");

        assertEquals(pendingId, result.pendingRequestId());
        assertFalse(result.approved());
    }

    // --- resume envelope (F-05 approval-node resume) ---------------------------

    @Test
    void authorizeSensitiveNodeWithNodeInputRecordsAResumeEnvelopeWithTenantContext() throws Exception {
        when(approvalGateService.evaluate(any(), any(), any(), any())).thenReturn(ApprovalEvaluation.autoApproved());
        WorkflowNodeDto approvalNode = node("n1", "approval", Map.of());
        com.agenticai.core.security.TenantContext.set(
            com.agenticai.core.security.TenantContext.of("tenant-a", "user-sub-1", java.util.Set.of()));
        try {
            // Deliberately quote-and-backslash-laden input: the old
            // hand-escaped payload concat is exactly what this would break.
            orchestrator.authorizeSensitiveNode(
                "wf", "run-1", approvalNode, "s", "u", "she said \"run\\it\"");
        } finally {
            com.agenticai.core.security.TenantContext.clear();
        }

        org.mockito.ArgumentCaptor<String> payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(approvalGateService).evaluate(any(), any(), payloadCaptor.capture(), any());
        com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().readTree(payloadCaptor.getValue());
        // Pre-existing top-level keys keep their exact shape (ApprovalController
        // reads adminOnly; the RL feedback hook reads runId).
        assertEquals("run-1", payload.path("runId").asText());
        assertEquals("n1", payload.path("nodeId").asText());
        assertFalse(payload.path("adminOnly").asBoolean());
        assertEquals("she said \"run\\it\"", payload.path("resume").path("nodeInput").asText());
        assertEquals("tenant-a", payload.path("resume").path("tenantId").asText());
        assertEquals("user-sub-1", payload.path("resume").path("subject").asText());
    }

    @Test
    void authorizeSensitiveNodeOmitsOversizedNodeInputInsteadOfTruncatingIt() throws Exception {
        // Truncated input would corrupt every downstream node on resume -
        // the envelope is omitted entirely and the resume executor declines
        // with an honest message instead.
        when(approvalGateService.evaluate(any(), any(), any(), any())).thenReturn(ApprovalEvaluation.autoApproved());
        WorkflowNodeDto approvalNode = node("n1", "approval", Map.of());
        String oversized = "x".repeat(OrchestratorService.MAX_RECORDED_NODE_INPUT_CHARS + 1);

        orchestrator.authorizeSensitiveNode("wf", "run-1", approvalNode, "s", "u", oversized);

        org.mockito.ArgumentCaptor<String> payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(approvalGateService).evaluate(any(), any(), payloadCaptor.capture(), any());
        com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().readTree(payloadCaptor.getValue());
        assertTrue(payload.path("resume").isMissingNode(),
            "oversized input must omit the resume envelope, got: " + payload.path("resume"));
        assertEquals("run-1", payload.path("runId").asText());
    }
}
