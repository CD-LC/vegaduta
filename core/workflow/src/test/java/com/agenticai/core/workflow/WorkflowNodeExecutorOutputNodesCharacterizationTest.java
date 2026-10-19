package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.omnichannel.ChannelException;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.agenticai.core.workflow.humaninput.WorkflowInputRequest;
import com.agenticai.core.workflow.humaninput.WorkflowInputRequestService;
import com.agenticai.core.workflow.voicemenu.WorkflowVoiceMenuRequestService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Characterisation of {@link WorkflowNodeExecutor}: output, workflow, human-input / voice-menu, bulk-email and data nodes. */
@SuppressWarnings("unchecked")
class WorkflowNodeExecutorOutputNodesCharacterizationTest extends WorkflowNodeExecutorCharacterizationTestBase {

    // ---- output ------------------------------------------------------------------------

    @Test
    void outputDefaultLogsAndTruncatesSummary() {
        NodeExecutionResult r = run(node("output", Map.of()), "result");
        assertEquals("OK", r.status());
        assertEquals("result", r.summary());
        assertEquals(List.of("Logged output (6 chars) - no external destination configured."), r.logLines());
    }

    @Test
    void outputEventPublishes() {
        NodeExecutionResult r = run(node("output", Map.of("channel", "event")), "m");
        assertEquals("Published to workflow.events.", r.summary());
        verify(events)
                .publish(
                        "workflow.output.emitted",
                        RUN,
                        Map.of("workflowId", WF.toString(), "nodeId", "n1", "message", "m"));
    }

    @Test
    void outputFileTextAndPdf() {
        NodeExecutionResult text = run(node("output", Map.of("channel", "file")), "body");
        assertEquals("workflow-output.txt", text.fileName());
        assertEquals("body", text.fileData());
        assertEquals("Saved as workflow-output.txt - downloadable from this run.", text.summary());

        when(pdf.renderToBase64("wf", "body")).thenReturn("B64");
        NodeExecutionResult p =
                run(node("output", Map.of("channel", "file", "fileType", "pdf", "fileName", "report")), "body");
        assertEquals("report.pdf", p.fileName());
        assertEquals("B64", p.fileData());
    }

    @Test
    void outputSafeFileName() {
        assertEquals("a_b", OutputNodeHandler.safeFileName("a/b", "fb"));
        assertEquals("fb", OutputNodeHandler.safeFileName("...", "fb"));
        assertEquals("fb", OutputNodeHandler.safeFileName(null, "fb"));
        assertEquals(200, OutputNodeHandler.safeFileName("x".repeat(300) + ".csv", "fb").length());
    }

    @Test
    void outputExternalDestinations() {
        when(email.send(eq("a@b.co"), eq("Workflow output: wf"), eq("m"), eq(RUN), eq("n1"))).thenReturn("email sent");
        assertEquals("email sent", run(node("output", Map.of("channel", "email", "emailTo", "a@b.co")), "m").summary());

        when(telegram.send("42", "m", RUN, "n1")).thenReturn("tg sent");
        assertEquals("tg sent", run(node("output", Map.of("channel", "telegram", "telegramChatId", "42")), "m").summary());

        when(teams.send("conv", "m", RUN, "n1")).thenReturn("teams sent");
        assertEquals("teams sent", run(node("output", Map.of("channel", "teams", "teamsConversationId", "conv")), "m").summary());

        when(whatsapp.send("+1", "m", RUN, "n1")).thenReturn("wa sent");
        assertEquals("wa sent", run(node("output", Map.of("channel", "whatsapp", "whatsappTo", "+1")), "m").summary());

        when(drive.upload("folder", "f.txt", "m", RUN, "n1")).thenReturn("drive ok");
        assertEquals(
                "drive ok",
                run(node("output", Map.of("channel", "google_drive", "driveFolderId", "folder", "driveFileName", "f.txt")), "m")
                        .summary());
    }

    @Test
    void outputChannelFailureIsSkipped() {
        when(email.send(any(), any(), any(), any(), any())).thenThrow(new ChannelException("email", "smtp down") {});
        NodeExecutionResult r = run(node("output", Map.of("channel", "email", "emailTo", "a@b.co")), "m");
        assertEquals("SKIPPED", r.status());
        assertEquals("smtp down", r.summary());
        assertEquals("Destination 'email' failed: smtp down", r.logLines().get(r.logLines().size() - 1));
    }

    // ---- workflow trigger --------------------------------------------------------------

    @Test
    void workflowNodeGuards() {
        assertEquals("No target workflow selected for this Workflow node.", run(node("workflow", Map.of()), "x").summary());
        assertEquals(
                "Invalid target workflow id: zz",
                run(node("workflow", Map.of("targetWorkflowId", "zz")), "x").summary());
        assertTrue(
                run(node("workflow", Map.of("targetWorkflowId", WF.toString())), "x")
                        .summary()
                        .startsWith("This node targets its own containing workflow"));
        UUID other = UUID.randomUUID();
        assertEquals(
                "Workflow triggering is unavailable right now (service not ready).",
                run(node("workflow", Map.of("targetWorkflowId", other.toString())), "x").summary());
    }

    @Test
    void workflowNodeRunsSubWorkflow() {
        UUID other = UUID.randomUUID();
        WorkflowService svc = mock(WorkflowService.class);
        when(workflowService.getIfAvailable()).thenReturn(svc);
        WorkflowRunResponse sub = mock(WorkflowRunResponse.class);
        when(sub.trace()).thenReturn(List.of());
        when(sub.workflowName()).thenReturn("child");
        when(sub.status()).thenReturn(WorkflowRunStatus.COMPLETED);
        when(sub.id()).thenReturn(UUID.randomUUID());
        when(svc.run(other, "in", "workflow:" + WF)).thenReturn(sub);
        NodeExecutionResult ok = run(node("workflow", Map.of("targetWorkflowId", other.toString())), "in");
        assertEquals("OK", ok.status());
        assertEquals("in", ok.output());
        assertEquals("Triggered 'child' - COMPLETED.", ok.summary());

        when(sub.status()).thenReturn(WorkflowRunStatus.FAILED);
        NodeExecutionResult failed = run(node("workflow", Map.of("targetWorkflowId", other.toString())), "in");
        assertEquals("SKIPPED", failed.status());
        assertEquals("Triggered 'child' - FAILED.", failed.summary());

        when(svc.run(any(), any(), any())).thenThrow(new RuntimeException("kaput"));
        NodeExecutionResult threw = run(node("workflow", Map.of("targetWorkflowId", other.toString())), "in");
        assertEquals("Failed to trigger workflow: kaput", threw.summary());
    }

    // ---- human input / voice menu ------------------------------------------------------

    @Test
    void humanInputUnavailableHaltsAndResumes() {
        NodeExecutionResult passthrough = run(node("human_input", Map.of()), "x");
        assertEquals("OK", passthrough.status());
        assertEquals("Human-input service unavailable - passed input through.", passthrough.summary());

        WorkflowInputRequestService svc = mock(WorkflowInputRequestService.class);
        WorkflowInputRequest req = mock(WorkflowInputRequest.class);
        UUID reqId = UUID.randomUUID();
        when(req.getId()).thenReturn(reqId);
        when(inputProvider.getIfAvailable()).thenReturn(svc);
        when(svc.createPending(any(), any(), any(), any(), any(), any())).thenReturn(req);
        NodeExecutionResult halted = run(node("human_input", Map.of()), "x");
        assertEquals(WorkflowRunStatus.AWAITING_INPUT, halted.haltRun());
        assertEquals("Awaiting human input (request " + reqId + ").", halted.summary());

        ApprovalReplayGrant.grant(WorkflowInputRequestService.REPLAY_ACTION_TYPE);
        NodeExecutionResult resumed = run(node("human_input", Map.of()), "x");
        assertEquals("Human input submitted - run resumed.", resumed.summary());
    }

    @Test
    void voiceMenuUnavailablePassesThroughAndResumeRoutesOnBranch() {
        assertEquals(
                "Voice menu service unavailable - passed input through.",
                run(node("voice_menu", Map.of()), "x").summary());
        ApprovalReplayGrant.grant(WorkflowVoiceMenuRequestService.REPLAY_ACTION_TYPE);
        NodeExecutionResult r = run(node("voice_menu", Map.of()), "{\"digits\":\"1\",\"branch\":\"sales\"}");
        assertEquals("sales", r.branch());
        assertEquals("Caller pressed '1' - routed to 'sales'.", r.summary());
    }

    // ---- bulk email / optional handlers / data nodes -----------------------------------

    @Test
    void bulkEmailUnavailableWhenHandlerAbsent() {
        WorkflowNodeExecutor bare = build(null);
        NodeExecutionResult r = bare.executeNode(WF.toString(), "wf", RUN, node("bulk_email", Map.of()), "x", "t");
        assertEquals("SKIPPED", r.status());
        assertEquals("Bulk Email is not available in this deployment.", r.summary());
    }

    @Test
    void bulkEmailDelegatesToHandler() {
        NodeExecutionResult expected = NodeExecutionResult.ok("sent", "done", null);
        when(bulk.execute(eq(RUN), any(WorkflowNodeDto.class), eq("x"), any())).thenReturn(expected);
        assertEquals(expected, run(node("bulk_email", Map.of()), "x"));
    }

    @Test
    void optionalHandlersReportUnavailable() {
        NodeExecutionResult crm = run(node("crm_lead", Map.of()), "x");
        assertEquals("SKIPPED", crm.status());
        assertEquals("CRM lead node unavailable - input passed through unchanged.", crm.summary());
        NodeExecutionResult table = run(node("data_table", Map.of()), "x");
        assertEquals("Data table node unavailable - input passed through unchanged.", table.summary());
        for (String t : List.of("question_classifier", "parameter_extractor", "variable_aggregator", "document_extractor")) {
            NodeExecutionResult r = run(node(t, Map.of()), "x");
            assertEquals("SKIPPED", r.status());
            assertEquals("AI node handler unavailable - input passed through unchanged.", r.summary());
        }
    }

    @Test
    void dataNodesAreDispatched() {
        NodeExecutionResult limit =
                run(node("limit", Map.of("count", 1)), "[1,2,3]");
        assertEquals("OK", limit.status());
        assertEquals("[1]", limit.output().replaceAll("\\s", ""));
        NodeExecutionResult set =
                run(node("set", Map.of("assignments", List.of(Map.of("name", "a", "value", "1")))), "{}");
        assertEquals("OK", set.status());
        assertTrue(set.output().contains("\"a\""));
    }

    @Test
    void nodeWithNullTypeFallsToDefault() {
        NodeExecutionResult r = run(node(null, Map.of()), "x");
        assertEquals("OK", r.status());
        assertNull(r.branch());
        assertEquals("Node type 'null' is not yet executable - passed input through unchanged.", r.summary());
    }
}
