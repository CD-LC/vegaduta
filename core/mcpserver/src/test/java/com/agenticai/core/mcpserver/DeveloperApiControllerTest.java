package com.agenticai.core.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowRunNotFoundException;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.WorkflowStatus;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

/**
 * {@link DeveloperApiController} is the plain-REST twin of the MCP tool callbacks - same API key,
 * same grant, same {@link McpCallerContext} enforcement, just {@code curl}-able. Because it shares
 * the authorization model rather than the code with {@code McpCallAgentToolCallback}/{@code
 * McpCallWorkflowToolCallback}, its exposed-set checks need their own tests: a regression here
 * would open the {@code /api/dev/v1} surface even while the MCP tools stayed correctly locked. HTTP
 * semantics differ too and are pinned deliberately - REST callers get real status codes (403 for
 * out-of-scope, 422 for a missing message, 502 for an upstream failure) where MCP callers get JSON
 * error strings.
 */
class DeveloperApiControllerTest {

    private AgentChatService agentChatService;
    private AgentService agentService;
    private WorkflowService workflowService;
    private AuditLogService auditLogService;
    private DeveloperApiController controller;

    private final UUID grantId = UUID.randomUUID();
    private final UUID exposedAgentId = UUID.randomUUID();
    private final UUID exposedWorkflowId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentChatService = mock(AgentChatService.class);
        agentService = mock(AgentService.class);
        workflowService = mock(WorkflowService.class);
        auditLogService = mock(AuditLogService.class);
        controller =
                new DeveloperApiController(
                        agentChatService, agentService, workflowService, auditLogService);
        McpCallerContext.set(grantId, Set.of(exposedAgentId), Set.of(exposedWorkflowId), Set.of());
    }

    @AfterEach
    void tearDown() {
        McpCallerContext.clear();
    }

    @Test
    void chatWithoutACallerContextIsAnIllegalState() {
        McpCallerContext.clear();
        assertThrows(
                IllegalStateException.class,
                () ->
                        controller.chat(
                                exposedAgentId,
                                new DeveloperApiController.ChatRequest("hi", null)));
    }

    @Test
    void chatOutsideTheExposedSetIs403AndNeverReachesTheAgent() {
        UUID unexposedAgentId = UUID.randomUUID();

        ResponseEntity<?> response =
                controller.chat(
                        unexposedAgentId, new DeveloperApiController.ChatRequest("hi", null));

        assertEquals(403, response.getStatusCode().value());
        verify(agentChatService, never())
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null));
        verify(auditLogService)
                .record(
                        eq("dev-api:" + grantId),
                        eq("mcp.grant.dev_api_call"),
                        eq("Agent"),
                        eq(unexposedAgentId.toString()),
                        eq(AuditOutcome.FAILURE),
                        anyString());
    }

    @Test
    void chatWithABlankMessageIs422() {
        ResponseEntity<?> response =
                controller.chat(
                        exposedAgentId, new DeveloperApiController.ChatRequest("   ", null));

        assertEquals(422, response.getStatusCode().value());
        verify(agentChatService, never())
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null));
    }

    @Test
    void chatOnAnExposedAgentReturnsTheJoinedReplyAndAMintedSessionId() {
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), exposedAgentId)
                                                && r.sessionId() != null
                                                && java.util.Objects.equals(
                                                        r.subjectKey(), "dev-api:" + grantId)
                                                && java.util.Objects.equals(r.userMessage(), "hi")
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.just("Hello ", "world"));

        ResponseEntity<?> response =
                controller.chat(exposedAgentId, new DeveloperApiController.ChatRequest("hi", null));

        assertEquals(200, response.getStatusCode().value());
        DeveloperApiController.ChatResponse body =
                (DeveloperApiController.ChatResponse) response.getBody();
        assertEquals("Hello world", body.reply());
        // dev-api-prefixed (not mcp-) so an audit reader can tell WHICH
        // calling surface a session came through.
        assertTrue(body.sessionId().startsWith("dev-api-" + grantId + "-"));

        ArgumentCaptor<ChatTurnRequest> turns = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(agentChatService).chat(any(TurnContext.class), turns.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                turns.getAllValues().stream()
                        .allMatch(
                                r ->
                                        java.util.Objects.equals(r.agentId(), exposedAgentId)
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null));
        assertEquals(turns.getValue().sessionId(), body.sessionId());
    }

    @Test
    void chatFailureIs502NotAThrownException() {
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.error(new RuntimeException("provider down")));

        ResponseEntity<?> response =
                controller.chat(exposedAgentId, new DeveloperApiController.ChatRequest("hi", null));

        // 502, not 500: the platform worked, the upstream agent call failed -
        // the status a programmatic caller retries on.
        assertEquals(502, response.getStatusCode().value());
    }

    @Test
    void runOutsideTheExposedSetIs403AndNeverStartsTheWorkflow() {
        UUID unexposedWorkflowId = UUID.randomUUID();

        ResponseEntity<?> response =
                controller.run(unexposedWorkflowId, new DeveloperApiController.RunRequest("go"));

        assertEquals(403, response.getStatusCode().value());
        verify(workflowService, never()).run(any(), any(), anyString());
        verify(auditLogService)
                .record(
                        eq("dev-api:" + grantId),
                        eq("mcp.grant.dev_api_call"),
                        eq("Workflow"),
                        eq(unexposedWorkflowId.toString()),
                        eq(AuditOutcome.FAILURE),
                        anyString());
    }

    @Test
    void runOnAnExposedWorkflowPassesTheGrantTraceableRequestedBy() {
        WorkflowRunResponse run =
                new WorkflowRunResponse(
                        UUID.randomUUID(),
                        exposedWorkflowId,
                        "wf",
                        WorkflowRunStatus.COMPLETED,
                        "dev-api:" + grantId,
                        null,
                        List.of(),
                        Instant.now(),
                        Instant.now(),
                        null,
                        null);
        when(workflowService.run(exposedWorkflowId, "go", "dev-api:" + grantId)).thenReturn(run);

        ResponseEntity<?> response =
                controller.run(exposedWorkflowId, new DeveloperApiController.RunRequest("go"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(run, response.getBody());
        verify(auditLogService)
                .record(
                        eq("dev-api:" + grantId),
                        eq("mcp.grant.dev_api_call"),
                        eq("Workflow"),
                        eq(exposedWorkflowId.toString()),
                        eq(AuditOutcome.SUCCESS),
                        anyString());
    }

    @Test
    void runWithNoBodyAtAllStillWorks() {
        // @RequestBody(required = false) - triggering a workflow with no
        // input is the common curl case and must not 400.
        WorkflowRunResponse run =
                new WorkflowRunResponse(
                        UUID.randomUUID(),
                        exposedWorkflowId,
                        "wf",
                        WorkflowRunStatus.COMPLETED,
                        "dev-api:" + grantId,
                        null,
                        List.of(),
                        Instant.now(),
                        Instant.now(),
                        null,
                        null);
        when(workflowService.run(eq(exposedWorkflowId), isNull(), anyString())).thenReturn(run);

        ResponseEntity<?> response = controller.run(exposedWorkflowId, null);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void runFailureIs502NotAThrownException() {
        when(workflowService.run(any(), any(), anyString()))
                .thenThrow(new RuntimeException("engine down"));

        ResponseEntity<?> response =
                controller.run(exposedWorkflowId, new DeveloperApiController.RunRequest("go"));

        assertEquals(502, response.getStatusCode().value());
    }

    /**
     * Real entity + reflection-backfilled id, then the production mapper - same honesty rule as
     * McpListAgentsToolCallbackTest.
     */
    private AgentResponse agentNamed(UUID id, String name) {
        Agent agent =
                new Agent(
                        name,
                        "prompt",
                        ProviderName.OPENAI,
                        "gpt-4o",
                        1024,
                        0.7,
                        1.0,
                        false,
                        ExperienceMode.SIMPLE);
        ReflectionTestUtils.setField(agent, "id", id);
        return AgentResponse.from(agent);
    }

    private WorkflowDefinition workflowNamed(UUID id, String name) {
        WorkflowDefinition workflow =
                new WorkflowDefinition(name, "desc", "{}", WorkflowStatus.ACTIVE);
        ReflectionTestUtils.setField(workflow, "id", id);
        return workflow;
    }

    @Test
    void listAgentsReturnsIdAndNameForExactlyTheGrantsExposedSet() {
        when(agentService.getGrantedAgent(exposedAgentId))
                .thenReturn(agentNamed(exposedAgentId, "Support Bot"));

        ResponseEntity<List<DeveloperApiController.AgentSummaryDto>> response =
                controller.listAgents();

        assertEquals(200, response.getStatusCode().value());
        List<DeveloperApiController.AgentSummaryDto> body = response.getBody();
        assertEquals(1, body.size());
        assertEquals(exposedAgentId, body.get(0).id());
        assertEquals("Support Bot", body.get(0).name());
        verify(auditLogService)
                .record(
                        eq("dev-api:" + grantId),
                        eq("mcp.grant.dev_api_call"),
                        eq("Agent"),
                        eq("list"),
                        eq(AuditOutcome.SUCCESS),
                        anyString());
    }

    @Test
    void listAgentsWithAnEmptyExposedSetIsAnEmptyListNotTheTenantCatalog() {
        // Deny-by-default made visible on the REST surface too: no exposed
        // agents means an empty list, never "list whatever the tenant has".
        McpCallerContext.set(grantId, Set.of(), Set.of(), Set.of());

        ResponseEntity<List<DeveloperApiController.AgentSummaryDto>> response =
                controller.listAgents();

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().isEmpty());
        verify(agentService, never()).get(any(UUID.class));
    }

    @Test
    void listWorkflowsReturnsIdAndNameForExactlyTheGrantsExposedSet() {
        when(workflowService.get(exposedWorkflowId))
                .thenReturn(workflowNamed(exposedWorkflowId, "Invoice Chaser"));

        ResponseEntity<List<DeveloperApiController.WorkflowSummaryDto>> response =
                controller.listWorkflows();

        assertEquals(200, response.getStatusCode().value());
        List<DeveloperApiController.WorkflowSummaryDto> body = response.getBody();
        assertEquals(1, body.size());
        assertEquals(exposedWorkflowId, body.get(0).id());
        assertEquals("Invoice Chaser", body.get(0).name());
        verify(auditLogService)
                .record(
                        eq("dev-api:" + grantId),
                        eq("mcp.grant.dev_api_call"),
                        eq("Workflow"),
                        eq("list"),
                        eq(AuditOutcome.SUCCESS),
                        anyString());
    }

    @Test
    void listWorkflowsWithAnEmptyExposedSetIsAnEmptyListNotTheTenantCatalog() {
        McpCallerContext.set(grantId, Set.of(), Set.of(), Set.of());

        ResponseEntity<List<DeveloperApiController.WorkflowSummaryDto>> response =
                controller.listWorkflows();

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().isEmpty());
        verify(workflowService, never()).get(any(UUID.class));
    }

    @Test
    void runStatusOutsideTheExposedSetIs403AndNeverLoadsTheRun() {
        UUID unexposedWorkflowId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        ResponseEntity<?> response = controller.runStatus(unexposedWorkflowId, runId);

        assertEquals(403, response.getStatusCode().value());
        verify(workflowService, never()).getRun(any(), any());
        verify(auditLogService)
                .record(
                        eq("dev-api:" + grantId),
                        eq("mcp.grant.dev_api_call"),
                        eq("Workflow"),
                        eq(unexposedWorkflowId.toString()),
                        eq(AuditOutcome.FAILURE),
                        anyString());
    }

    @Test
    void runStatusOnAnExposedWorkflowReturnsTheSameDtoAsTheJwtSurface() {
        UUID runId = UUID.randomUUID();
        WorkflowRunResponse run =
                new WorkflowRunResponse(
                        runId,
                        exposedWorkflowId,
                        "wf",
                        WorkflowRunStatus.RUNNING,
                        "dev-api:" + grantId,
                        null,
                        List.of(),
                        Instant.now(),
                        null,
                        null,
                        null);
        when(workflowService.getRun(exposedWorkflowId, runId)).thenReturn(run);

        ResponseEntity<?> response = controller.runStatus(exposedWorkflowId, runId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(run, response.getBody());
        verify(auditLogService)
                .record(
                        eq("dev-api:" + grantId),
                        eq("mcp.grant.dev_api_call"),
                        eq("Workflow"),
                        eq(exposedWorkflowId.toString()),
                        eq(AuditOutcome.SUCCESS),
                        anyString());
    }

    @Test
    void runStatusForAMissingOrMismatchedRunIs404NotA502() {
        // WorkflowService#getRun loads via findByIdAndWorkflowId - a run
        // belonging to a DIFFERENT workflow surfaces as not-found, and a poll
        // client must be able to tell "gone" (404, stop polling) from
        // "platform hiccup" (502, retry).
        UUID runId = UUID.randomUUID();
        when(workflowService.getRun(exposedWorkflowId, runId))
                .thenThrow(new WorkflowRunNotFoundException(runId));

        ResponseEntity<?> response = controller.runStatus(exposedWorkflowId, runId);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void runStatusFailureIs502NotAThrownException() {
        UUID runId = UUID.randomUUID();
        when(workflowService.getRun(exposedWorkflowId, runId))
                .thenThrow(new RuntimeException("db down: jdbc:postgresql://pg-internal:5432"));

        ResponseEntity<?> response = controller.runStatus(exposedWorkflowId, runId);

        assertEquals(502, response.getStatusCode().value());
        // The exception text is logged, not echoed: a caller must not learn internal
        // hostnames or driver detail from the error body.
        String body = String.valueOf(response.getBody());
        assertTrue(body.contains("Run status read failed"));
        assertFalse(body.contains("db down"));
        assertFalse(body.contains("pg-internal"));
    }
}
