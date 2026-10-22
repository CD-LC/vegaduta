package com.agenticai.core.mcpserver.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.mcpserver.McpCallerContextTestAccess;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code call_workflow} is the heaviest thing an external key can trigger - one tool call can fan
 * out into a whole author-drawn graph of agent turns and external side effects (class javadoc's
 * honesty note), which makes its per-grant exposed-workflow check strictly MORE consequential than
 * call_agent's, not a copy-paste formality. Also pinned: {@code AWAITING_APPROVAL} passes through
 * as a legitimate terminal status (a human still has to act in the HITL inbox - reporting it as an
 * error would make callers retry and pile up duplicate pending runs), and the grant-traceable
 * {@code requestedBy} convention.
 */
class McpCallWorkflowToolCallbackTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private WorkflowService workflowService;
    private AuditLogService auditLogService;
    private McpCallWorkflowToolCallback callback;

    private final UUID grantId = UUID.randomUUID();
    private final UUID exposedWorkflowId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        auditLogService = mock(AuditLogService.class);
        callback = new McpCallWorkflowToolCallback(workflowService, auditLogService);
        McpCallerContextTestAccess.set(grantId, Set.of(), Set.of(exposedWorkflowId), Set.of());
    }

    @AfterEach
    void tearDown() {
        McpCallerContextTestAccess.clear();
    }

    private JsonNode call(String toolInput) throws Exception {
        return MAPPER.readTree(callback.call(toolInput));
    }

    private WorkflowRunResponse runResponse(
            WorkflowRunStatus status, String errorMessage, List<NodeTraceEntry> trace) {
        return new WorkflowRunResponse(
                UUID.randomUUID(),
                exposedWorkflowId,
                "invoice-chaser",
                status,
                "mcp-external:" + grantId,
                errorMessage,
                trace,
                Instant.now(),
                Instant.now(),
                null,
                null);
    }

    @Test
    void missingCallerContextIsAnIllegalState() {
        McpCallerContextTestAccess.clear();
        assertThrows(IllegalStateException.class, () -> callback.call("{}"));
    }

    @Test
    void missingWorkflowIdReturnsAnError() throws Exception {
        assertTrue(call("{}").has("error"));
        verify(workflowService, never()).run(any(), any(), anyString());
    }

    @Test
    void malformedWorkflowIdReturnsAGuidingError() throws Exception {
        JsonNode result = call("{\"workflow_id\": \"not-a-uuid\"}");
        assertTrue(result.get("error").asString().contains("list_my_workflows"));
        verify(workflowService, never()).run(any(), any(), anyString());
    }

    @Test
    void aWorkflowOutsideTheGrantsExposedSetIsRejectedBeforeRunIsEverInvoked() throws Exception {
        // Same authorization line as call_agent, higher stakes: an unexposed
        // id must never start a multi-node run with real side effects.
        UUID unexposedWorkflowId = UUID.randomUUID();

        JsonNode result = call("{\"workflow_id\": \"" + unexposedWorkflowId + "\"}");

        assertTrue(result.get("error").asString().contains("not authorized"));
        verify(workflowService, never()).run(any(), any(), anyString());
        // A rejected over-reach attempt is itself compliance-relevant.
        verify(auditLogService)
                .record(
                        eq("mcp-external:" + grantId),
                        eq("mcp.grant.call_workflow"),
                        eq("Workflow"),
                        eq(unexposedWorkflowId.toString()),
                        eq(AuditOutcome.FAILURE),
                        anyString());
    }

    @Test
    void anExposedWorkflowRunsWithTheGrantTraceableRequestedBy() throws Exception {
        when(workflowService.run(eq(exposedWorkflowId), eq("go"), eq("mcp-external:" + grantId)))
                .thenReturn(
                        runResponse(
                                WorkflowRunStatus.COMPLETED,
                                null,
                                List.of(
                                        new NodeTraceEntry(
                                                "n1",
                                                "AGENT",
                                                "Draft email",
                                                "COMPLETED",
                                                "drafted",
                                                Instant.now(),
                                                Instant.now()))));

        JsonNode result =
                call("{\"workflow_id\": \"" + exposedWorkflowId + "\", \"input\": \"go\"}");

        assertEquals("COMPLETED", result.get("status").asString());
        assertEquals(exposedWorkflowId.toString(), result.get("workflow_id").asString());
        assertNull(result.get("error_message"), "a clean run must not carry an error_message key");
        // The trace uses the human-readable label when present - what an
        // external caller actually reads to understand what their run did.
        assertEquals("Draft email", result.get("trace").get(0).get("node").asString());
        verify(auditLogService)
                .record(
                        eq("mcp-external:" + grantId),
                        eq("mcp.grant.call_workflow"),
                        eq("Workflow"),
                        eq(exposedWorkflowId.toString()),
                        eq(AuditOutcome.SUCCESS),
                        anyString());
    }

    @Test
    void omittedInputBecomesAnEmptyStringNotNull() throws Exception {
        // WorkflowService#run receives "" for an absent input - passing null
        // through would shift the null-handling burden onto every node.
        when(workflowService.run(eq(exposedWorkflowId), eq(""), anyString()))
                .thenReturn(runResponse(WorkflowRunStatus.COMPLETED, null, List.of()));

        JsonNode result = call("{\"workflow_id\": \"" + exposedWorkflowId + "\"}");

        assertEquals("COMPLETED", result.get("status").asString());
        verify(workflowService).run(eq(exposedWorkflowId), eq(""), eq("mcp-external:" + grantId));
    }

    @Test
    void awaitingApprovalIsReportedAsAStatusNotAnError() throws Exception {
        when(workflowService.run(eq(exposedWorkflowId), anyString(), anyString()))
                .thenReturn(runResponse(WorkflowRunStatus.AWAITING_APPROVAL, null, List.of()));

        JsonNode result =
                call("{\"workflow_id\": \"" + exposedWorkflowId + "\", \"input\": \"go\"}");

        assertEquals("AWAITING_APPROVAL", result.get("status").asString());
        assertNull(
                result.get("error"),
                "a suspended run is a legitimate terminal state for this call");
    }

    @Test
    void aFailedRunSurfacesItsErrorMessageAlongsideTheFailedStatus() throws Exception {
        when(workflowService.run(eq(exposedWorkflowId), anyString(), anyString()))
                .thenReturn(runResponse(WorkflowRunStatus.FAILED, "node n2 timed out", List.of()));

        JsonNode result =
                call("{\"workflow_id\": \"" + exposedWorkflowId + "\", \"input\": \"go\"}");

        assertEquals("FAILED", result.get("status").asString());
        assertEquals("node n2 timed out", result.get("error_message").asString());
    }

    @Test
    void aRunThatThrowsBecomesAnErrorPayloadNotAThrownException() throws Exception {
        when(workflowService.run(any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("engine unavailable"));

        JsonNode result =
                call("{\"workflow_id\": \"" + exposedWorkflowId + "\", \"input\": \"go\"}");

        assertTrue(result.get("error").asString().contains("Workflow call failed"));
        verify(auditLogService)
                .record(
                        eq("mcp-external:" + grantId),
                        eq("mcp.grant.call_workflow"),
                        eq("Workflow"),
                        isNull(),
                        eq(AuditOutcome.FAILURE),
                        anyString());
    }

    @Test
    void anAuditLoggingFailureNeverChangesTheSuccessfulResult() throws Exception {
        // "Never let logging break the real call" - same posture McpCallAgentToolCallback pins.
        when(workflowService.run(eq(exposedWorkflowId), eq("go"), eq("mcp-external:" + grantId)))
                .thenReturn(runResponse(WorkflowRunStatus.COMPLETED, null, List.of()));
        doThrow(new RuntimeException("audit table unavailable"))
                .when(auditLogService)
                .record(anyString(), anyString(), anyString(), any(), any(), anyString());

        JsonNode result =
                call("{\"workflow_id\": \"" + exposedWorkflowId + "\", \"input\": \"go\"}");

        assertEquals("COMPLETED", result.get("status").asString());
    }
}
