package com.agenticai.core.mcpserver.expose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetExhaustedException;
import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestRepository;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.mcpserver.McpCallerContext;
import com.agenticai.core.mcpserver.McpCallerContextTestAccess;
import com.agenticai.core.mcpserver.McpServerRateLimiter;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.WorkflowBackgroundRunService;
import com.agenticai.core.workflow.WorkflowRunNotFoundException;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins what an external MCP caller can and cannot make a dynamic tool do: authorization is
 * re-checked at execution, every call (and refusal) is audited with channel=mcp, a parked approval
 * fails CLOSED (an error naming it, never the reply, never an auto-approve), budgets and rate
 * limits are consulted first, and a workflow run is bounded-waited and pollable.
 */
class McpExposedToolInvokerTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private AgentChatService agentChatService;
    private WorkflowService workflowService;
    private WorkflowBackgroundRunService backgroundRuns;
    private AuditLogService auditLogService;
    private McpServerRateLimiter rateLimiter;
    private PerUserBudgetService budgetService;
    private ApprovalRequestRepository approvals;
    private McpExposedToolCatalog catalog;
    private McpExposedToolInvoker invoker;

    private final UUID grantId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private McpCallerContext caller;

    private McpExposedTool agentTool;
    private McpExposedTool workflowTool;
    private McpExposedTool getRunTool;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        agentChatService = mock(AgentChatService.class);
        workflowService = mock(WorkflowService.class);
        backgroundRuns = mock(WorkflowBackgroundRunService.class);
        auditLogService = mock(AuditLogService.class);
        rateLimiter = mock(McpServerRateLimiter.class);
        budgetService = mock(PerUserBudgetService.class);
        approvals = mock(ApprovalRequestRepository.class);
        catalog = mock(McpExposedToolCatalog.class);
        when(rateLimiter.allowExposedInvocation(any(), anyLong())).thenReturn(true);
        when(approvals.findByStatusOrderByCreatedAtDesc(ApprovalStatus.PENDING))
                .thenReturn(List.of());

        ObjectProvider<PerUserBudgetService> budgetProvider = mock(ObjectProvider.class);
        when(budgetProvider.getIfAvailable()).thenReturn(budgetService);
        ObjectProvider<ApprovalRequestRepository> approvalProvider = mock(ObjectProvider.class);
        when(approvalProvider.getIfAvailable()).thenReturn(approvals);

        invoker = newInvoker(budgetProvider, approvalProvider, 5, 1);

        TenantContext.set(
                TenantContext.of(
                        "tenant_a",
                        "mcp-external:" + grantId,
                        Set.of("ROLE_mcp-external"),
                        grantId.toString()));
        McpCallerContextTestAccess.set(grantId, Set.of(agentId), Set.of(workflowId), Set.of());
        caller = McpCallerContext.current().orElseThrow();

        agentTool =
                new McpExposedTool(
                        "agent_helper", "d", "{}", McpExposedTool.Target.AGENT, agentId, "Helper");
        workflowTool =
                new McpExposedTool(
                        "workflow_flow",
                        "d",
                        "{}",
                        McpExposedTool.Target.WORKFLOW,
                        workflowId,
                        "Flow");
        getRunTool =
                new McpExposedTool(
                        "get_run", "d", "{}", McpExposedTool.Target.GET_RUN, null, "get_run");
    }

    private McpExposedToolInvoker newInvoker(
            ObjectProvider<PerUserBudgetService> budgetProvider,
            ObjectProvider<ApprovalRequestRepository> approvalProvider,
            long agentTimeoutSeconds,
            long workflowWaitSeconds) {
        return new McpExposedToolInvoker(
                agentChatService,
                workflowService,
                backgroundRuns,
                auditLogService,
                rateLimiter,
                budgetProvider,
                approvalProvider,
                catalog,
                new McpExposedToolInvoker.Settings(
                        agentTimeoutSeconds, workflowWaitSeconds, 1, 30, 1000, 5000));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        McpCallerContextTestAccess.clear();
    }

    private JsonNode args(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    private void agentReplies(String... chunks) {
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.just(chunks));
    }

    private static WorkflowRunResponse run(
            UUID id,
            UUID workflow,
            WorkflowRunStatus status,
            String error,
            List<NodeTraceEntry> trace) {
        return new WorkflowRunResponse(
                id,
                workflow,
                "Flow",
                status,
                "mcp-external:x",
                error,
                trace,
                Instant.now(),
                null,
                null,
                1);
    }

    private static NodeTraceEntry step(String output, String cost) {
        NodeTraceEntry entry =
                new NodeTraceEntry("n1", "agent", "Step", "COMPLETED", "did it", null, null)
                        .withPayloads("{}", false, output, false);
        return cost == null ? entry : entry.withUsage(1L, 1L, new BigDecimal(cost));
    }

    // ---- agents ------------------------------------------------------------------------------

    @Test
    void agentCallReturnsTheReplyThenAConversationAndCostNote() throws Exception {
        agentReplies("Hello ", "world");

        McpToolResult result = invoker.invoke(caller, agentTool, args("{\"message\":\"hi\"}"));

        assertFalse(result.error());
        assertEquals("Hello world", result.texts().get(0));
        assertTrue(result.texts().get(1).contains("conversationId="), result.texts().get(1));
        ArgumentCaptor<ChatTurnRequest> turns = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(agentChatService).chat(any(TurnContext.class), turns.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                turns.getAllValues().stream()
                        .allMatch(
                                r ->
                                        java.util.Objects.equals(r.agentId(), agentId)
                                                && java.util.Objects.equals(r.userMessage(), "hi")
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null));
        assertTrue(
                turns.getValue().sessionId().startsWith("mcp-" + grantId + "-"),
                turns.getValue().sessionId());
        assertEquals("mcp-external:" + grantId, turns.getValue().subjectKey());
    }

    @Test
    void everyAgentInvocationIsAuditedWithTheMcpChannelAndNeverTheMessageText() throws Exception {
        agentReplies("ok");

        invoker.invoke(caller, agentTool, args("{\"message\":\"super secret text\"}"));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditLogService)
                .record(
                        eq("mcp-external:" + grantId),
                        eq("mcp.expose.call_agent"),
                        eq("Agent"),
                        eq(agentId.toString()),
                        eq(AuditOutcome.SUCCESS),
                        detail.capture());
        assertTrue(detail.getValue().startsWith("[channel=mcp]"), detail.getValue());
        assertFalse(detail.getValue().contains("super secret"));
    }

    @Test
    void aSuppliedConversationIdIsNamespacedToTheGrant() throws Exception {
        agentReplies("ok");

        McpToolResult result =
                invoker.invoke(
                        caller,
                        agentTool,
                        args("{\"message\":\"hi\",\"conversationId\":\"abc-1\"}"));

        verify(agentChatService)
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), agentId)
                                                && java.util.Objects.equals(
                                                        r.sessionId(), "mcp-" + grantId + "-abc-1")
                                                && r.subjectKey() != null
                                                && java.util.Objects.equals(r.userMessage(), "hi")
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null));
        assertTrue(result.texts().get(1).contains("conversationId=abc-1"));
    }

    @Test
    void badArgumentsNeverReachTheAgent() throws Exception {
        assertTrue(invoker.invoke(caller, agentTool, args("{}")).error());
        assertTrue(invoker.invoke(caller, agentTool, args("{\"message\":\"   \"}")).error());
        assertTrue(
                invoker.invoke(
                                caller,
                                agentTool,
                                args("{\"message\":\"" + "x".repeat(1001) + "\"}"))
                        .error());
        assertTrue(
                invoker.invoke(
                                caller,
                                agentTool,
                                args("{\"message\":\"hi\",\"conversationId\":\"has space\"}"))
                        .error());
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
    void anAgentOutsideTheGrantIsRefusedAuditedAndNeverCalled() throws Exception {
        McpExposedTool other =
                new McpExposedTool(
                        "agent_other",
                        "d",
                        "{}",
                        McpExposedTool.Target.AGENT,
                        UUID.randomUUID(),
                        "Other");

        McpToolResult result = invoker.invoke(caller, other, args("{\"message\":\"hi\"}"));

        assertTrue(result.error());
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
                        eq("mcp-external:" + grantId),
                        eq("mcp.expose.denied"),
                        eq("McpTool"),
                        eq("agent_other"),
                        eq(AuditOutcome.FAILURE),
                        anyString());
    }

    @Test
    void aRateLimitedKeyIsRefusedBeforeAnyWork() throws Exception {
        when(rateLimiter.allowExposedInvocation(eq(grantId), anyLong())).thenReturn(false);

        McpToolResult result = invoker.invoke(caller, agentTool, args("{\"message\":\"hi\"}"));

        assertTrue(result.error());
        assertTrue(result.texts().get(0).contains("Too many"));
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
                        anyString(),
                        eq("mcp.expose.call_agent"),
                        anyString(),
                        anyString(),
                        eq(AuditOutcome.FAILURE),
                        anyString());
    }

    @Test
    void anExhaustedBudgetStopsTheCallWithTheBudgetMessage() throws Exception {
        doThrow(
                        new PerUserBudgetExhaustedException(
                                "Daily budget used up",
                                "text",
                                BigDecimal.ONE,
                                BigDecimal.ONE,
                                60L))
                .when(budgetService)
                .checkBudget(anyString(), anyString(), any(PerUserBudgetService.Modality.class));

        McpToolResult result = invoker.invoke(caller, agentTool, args("{\"message\":\"hi\"}"));

        assertTrue(result.error());
        assertEquals("Daily budget used up", result.texts().get(0));
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
    void aParkedApprovalFailsClosedWithAClearErrorInsteadOfTheReply() throws Exception {
        agentReplies("I have sent the email for you.");
        UUID approvalId = UUID.randomUUID();
        ApprovalRequest parked =
                new ApprovalRequest(
                        "mcp_tool.send_email",
                        "Tool call: send_email",
                        "{}",
                        "agent:" + agentId + ":session:mcp-" + grantId + "-conv1");
        ReflectionTestUtils.setField(parked, "id", approvalId);
        when(approvals.findByStatusOrderByCreatedAtDesc(ApprovalStatus.PENDING))
                .thenReturn(List.of(parked));

        McpToolResult result =
                invoker.invoke(
                        caller,
                        agentTool,
                        args("{\"message\":\"mail it\",\"conversationId\":\"conv1\"}"));

        assertTrue(result.error());
        assertEquals(1, result.texts().size());
        String text = result.texts().get(0);
        assertTrue(text.contains(approvalId.toString()), text);
        assertTrue(text.contains("NOT run"), text);
        assertTrue(text.contains("nothing was approved"), text);
        assertFalse(
                text.contains("sent the email"),
                "the model's reply must not be returned as if the action happened");
        verify(auditLogService)
                .record(
                        anyString(),
                        eq("mcp.expose.call_agent"),
                        anyString(),
                        anyString(),
                        eq(AuditOutcome.FAILURE),
                        anyString());
    }

    @Test
    void approvalsFromOtherSessionsOrBeforeThisCallDoNotFailTheCall() throws Exception {
        agentReplies("fine");
        ApprovalRequest otherSession =
                new ApprovalRequest(
                        "mcp_tool.x", "s", "{}", "agent:" + agentId + ":session:someone-else");
        ReflectionTestUtils.setField(otherSession, "id", UUID.randomUUID());
        ApprovalRequest stale =
                new ApprovalRequest(
                        "mcp_tool.x",
                        "s",
                        "{}",
                        "agent:" + agentId + ":session:mcp-" + grantId + "-conv2");
        ReflectionTestUtils.setField(stale, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(stale, "createdAt", Instant.now().minusSeconds(3600));
        when(approvals.findByStatusOrderByCreatedAtDesc(ApprovalStatus.PENDING))
                .thenReturn(List.of(otherSession, stale));

        McpToolResult result =
                invoker.invoke(
                        caller,
                        agentTool,
                        args("{\"message\":\"hi\",\"conversationId\":\"conv2\"}"));

        assertFalse(result.error());
        assertEquals("fine", result.texts().get(0));
    }

    @Test
    void costIsTheMeteredSpendDeltaAcrossTheCall() throws Exception {
        agentReplies("ok");
        when(budgetService.status(
                        anyString(), anyString(), any(PerUserBudgetService.Modality.class)))
                .thenReturn(
                        new PerUserBudgetService.BudgetStatus(
                                "text",
                                new BigDecimal("5.00"),
                                new BigDecimal("1.00"),
                                new BigDecimal("4.00"),
                                60L),
                        new PerUserBudgetService.BudgetStatus(
                                "text",
                                new BigDecimal("5.00"),
                                new BigDecimal("1.25"),
                                new BigDecimal("3.75"),
                                60L));

        McpToolResult result = invoker.invoke(caller, agentTool, args("{\"message\":\"hi\"}"));

        assertTrue(result.texts().get(1).contains("approx cost: $0.25"), result.texts().get(1));
    }

    @Test
    void anUnmeteredCallSaysSoInsteadOfInventingACost() throws Exception {
        agentReplies("ok");
        when(budgetService.status(
                        anyString(), anyString(), any(PerUserBudgetService.Modality.class)))
                .thenThrow(new IllegalStateException("redis down"));

        McpToolResult result = invoker.invoke(caller, agentTool, args("{\"message\":\"hi\"}"));

        assertFalse(result.error());
        assertTrue(result.texts().get(1).contains("cost: not metered"));
    }

    @Test
    void anAgentThatNeverFinishesTimesOutCleanly() throws Exception {
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.never());
        McpExposedToolInvoker fast = newInvoker(emptyProvider(), emptyProvider(), 1, 1);

        McpToolResult result = fast.invoke(caller, agentTool, args("{\"message\":\"hi\"}"));

        assertTrue(result.error());
        assertTrue(result.texts().get(0).contains("did not finish"), result.texts().get(0));
    }

    @Test
    void aThrowingAgentIsAToolErrorNotAnException() throws Exception {
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.error(new IllegalArgumentException("model exploded")));

        McpToolResult result = invoker.invoke(caller, agentTool, args("{\"message\":\"hi\"}"));

        assertTrue(result.error());
        assertTrue(result.texts().get(0).contains("model exploded"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    // ---- workflows ---------------------------------------------------------------------------

    @Test
    void aWorkflowIsStartedInTheBackgroundThenPolledToCompletion() throws Exception {
        UUID runId = UUID.randomUUID();
        when(backgroundRuns.start(
                        eq(workflowId),
                        eq("hello"),
                        eq("mcp-external:" + grantId),
                        isNull(),
                        eq(List.of())))
                .thenReturn(run(runId, workflowId, WorkflowRunStatus.RUNNING, null, List.of()));
        when(workflowService.getRun(workflowId, runId))
                .thenReturn(
                        run(runId, workflowId, WorkflowRunStatus.RUNNING, null, List.of()),
                        run(
                                runId,
                                workflowId,
                                WorkflowRunStatus.COMPLETED,
                                null,
                                List.of(step("\"the answer\"", "0.0100"))));

        McpToolResult result = invoker.invoke(caller, workflowTool, args("{\"input\":\"hello\"}"));

        assertFalse(result.error());
        JsonNode body = MAPPER.readTree(result.texts().get(0));
        assertEquals("COMPLETED", body.get("status").asString());
        assertEquals(runId.toString(), body.get("run_id").asString());
        assertEquals("\"the answer\"", body.get("output").asString());
        assertEquals("0.0100", body.get("approx_cost_usd").asString());
        verify(auditLogService)
                .record(
                        eq("mcp-external:" + grantId),
                        eq("mcp.expose.run_workflow"),
                        eq("Workflow"),
                        eq(workflowId.toString()),
                        eq(AuditOutcome.SUCCESS),
                        anyString());
    }

    @Test
    void aLongRunReturnsRunningWithARunIdAndAPollHint() throws Exception {
        UUID runId = UUID.randomUUID();
        when(backgroundRuns.start(any(), anyString(), anyString(), any(), any()))
                .thenReturn(run(runId, workflowId, WorkflowRunStatus.RUNNING, null, List.of()));
        McpExposedToolInvoker noWait = newInvoker(emptyProvider(), emptyProvider(), 5, 0);

        McpToolResult result = noWait.invoke(caller, workflowTool, args("{}"));

        assertFalse(result.error());
        JsonNode body = MAPPER.readTree(result.texts().get(0));
        assertEquals("RUNNING", body.get("status").asString());
        assertEquals(runId.toString(), body.get("run_id").asString());
        assertTrue(body.get("hint").asString().contains("get_run"));
        verify(workflowService, never()).getRun(any(), any());
    }

    @Test
    void aRunAwaitingApprovalFailsClosedAndNothingIsApproved() throws Exception {
        UUID runId = UUID.randomUUID();
        when(backgroundRuns.start(any(), anyString(), anyString(), any(), any()))
                .thenReturn(
                        run(
                                runId,
                                workflowId,
                                WorkflowRunStatus.AWAITING_APPROVAL,
                                null,
                                List.of()));

        McpToolResult result = invoker.invoke(caller, workflowTool, args("{}"));

        assertTrue(result.error());
        String text = result.texts().get(0);
        assertTrue(text.contains(runId.toString()), text);
        assertTrue(text.contains("AWAITING_APPROVAL"), text);
        assertTrue(text.contains("nothing was approved"), text);
    }

    @Test
    void aCompletedRunWithAParkedToolApprovalStillFailsClosed() throws Exception {
        UUID runId = UUID.randomUUID();
        when(backgroundRuns.start(any(), anyString(), anyString(), any(), any()))
                .thenReturn(
                        run(
                                runId,
                                workflowId,
                                WorkflowRunStatus.COMPLETED,
                                null,
                                List.of(step("\"x\"", null))));
        ApprovalRequest parked =
                new ApprovalRequest(
                        "mcp_tool.pay", "s", "{}", "agent:" + agentId + ":session:" + runId);
        ReflectionTestUtils.setField(parked, "id", UUID.randomUUID());
        when(approvals.findByStatusOrderByCreatedAtDesc(ApprovalStatus.PENDING))
                .thenReturn(List.of(parked));

        McpToolResult result = invoker.invoke(caller, workflowTool, args("{}"));

        assertTrue(result.error());
        assertTrue(result.texts().get(0).contains("pending approvals"));
    }

    @Test
    void aFailedRunIsAnErrorCarryingTheRunIdAndMessage() throws Exception {
        UUID runId = UUID.randomUUID();
        when(backgroundRuns.start(any(), anyString(), anyString(), any(), any()))
                .thenReturn(
                        run(
                                runId,
                                workflowId,
                                WorkflowRunStatus.FAILED,
                                "node exploded",
                                List.of()));

        McpToolResult result = invoker.invoke(caller, workflowTool, args("{}"));

        assertTrue(result.error());
        JsonNode body = MAPPER.readTree(result.texts().get(0));
        assertEquals("FAILED", body.get("status").asString());
        assertEquals("node exploded", body.get("error_message").asString());
    }

    @Test
    void aWorkflowOutsideTheGrantIsRefusedAndNeverStarted() throws Exception {
        McpExposedTool other =
                new McpExposedTool(
                        "workflow_other",
                        "d",
                        "{}",
                        McpExposedTool.Target.WORKFLOW,
                        UUID.randomUUID(),
                        "Other");

        McpToolResult result = invoker.invoke(caller, other, args("{}"));

        assertTrue(result.error());
        verify(backgroundRuns, never()).start(any(), anyString(), anyString(), any(), any());
    }

    // ---- get_run -----------------------------------------------------------------------------

    @Test
    void getRunFindsARunOnlyUnderWorkflowsTheCallerCanSee() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID foreignWorkflow = UUID.randomUUID();
        when(catalog.forCaller(caller)).thenReturn(List.of(workflowTool, getRunTool));
        when(workflowService.getRun(workflowId, runId))
                .thenReturn(
                        run(
                                runId,
                                workflowId,
                                WorkflowRunStatus.COMPLETED,
                                null,
                                List.of(step("\"done\"", null))));

        McpToolResult result =
                invoker.invoke(caller, getRunTool, args("{\"run_id\":\"" + runId + "\"}"));

        assertFalse(result.error());
        assertEquals("COMPLETED", MAPPER.readTree(result.texts().get(0)).get("status").asString());
        verify(workflowService, never()).getRun(eq(foreignWorkflow), any());
    }

    @Test
    void getRunOfARunUnderNoVisibleWorkflowIsNotFound() throws Exception {
        UUID runId = UUID.randomUUID();
        when(catalog.forCaller(caller)).thenReturn(List.of(workflowTool, getRunTool));
        when(workflowService.getRun(workflowId, runId))
                .thenThrow(new WorkflowRunNotFoundException(runId));

        McpToolResult result =
                invoker.invoke(caller, getRunTool, args("{\"run_id\":\"" + runId + "\"}"));

        assertTrue(result.error());
        assertTrue(result.texts().get(0).contains("No run"), result.texts().get(0));
        verify(auditLogService)
                .record(
                        anyString(),
                        eq("mcp.expose.get_run"),
                        anyString(),
                        any(),
                        eq(AuditOutcome.FAILURE),
                        anyString());
    }

    @Test
    void getRunRejectsAMissingOrMalformedRunId() throws Exception {
        assertTrue(invoker.invoke(caller, getRunTool, args("{}")).error());
        assertTrue(invoker.invoke(caller, getRunTool, args("{\"run_id\":\"not-a-uuid\"}")).error());
    }
}
