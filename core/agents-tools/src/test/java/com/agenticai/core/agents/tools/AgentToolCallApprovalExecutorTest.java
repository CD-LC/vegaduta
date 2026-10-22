package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJobLookup;
import com.agenticai.core.agents.job.AgentJobLookup.JobFacts;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestExecutionRecordedEvent;
import com.agenticai.core.approval.ApprovalRequestRepository;
import com.agenticai.core.approval.RetryableActionFailure;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link AgentToolCallApprovalExecutor}'s replay contract: the approved tool runs with the recorded
 * arguments under the recorded tenant (context restored afterwards, success OR failure), the result
 * is persisted onto the request and notified, an unresolvable tool is a NON-retryable failure, and
 * a pre-envelope legacy row still replays via its {@code requestedBy} label.
 */
class AgentToolCallApprovalExecutorTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String TOOL_NAME = "send_message";
    private static final String ACTION_TYPE = "mcp_tool." + TOOL_NAME;

    private AgentToolProvider toolProvider;
    private ApprovalRequestRepository requestRepository;
    private NotificationService notificationService;
    private SessionMemoryService sessionMemoryService;
    private AgentJobLookup agentJobLookup;
    private ApplicationEventPublisher eventPublisher;
    private AgentToolCallApprovalExecutor executor;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        toolProvider = mock(AgentToolProvider.class);
        requestRepository = mock(ApprovalRequestRepository.class);
        notificationService = mock(NotificationService.class);
        sessionMemoryService = mock(SessionMemoryService.class);

        ObjectProvider<AgentToolProvider> toolProviderProvider = mock(ObjectProvider.class);
        when(toolProviderProvider.getObject()).thenReturn(toolProvider);
        ObjectProvider<NotificationService> notificationProvider = mock(ObjectProvider.class);
        when(notificationProvider.getIfAvailable()).thenReturn(notificationService);
        ObjectProvider<SessionMemoryService> sessionMemoryProvider = mock(ObjectProvider.class);
        when(sessionMemoryProvider.getIfAvailable()).thenReturn(sessionMemoryService);
        agentJobLookup = mock(AgentJobLookup.class);
        ObjectProvider<AgentJobLookup> agentJobLookupProvider = mock(ObjectProvider.class);
        when(agentJobLookupProvider.getIfAvailable()).thenReturn(agentJobLookup);
        // Default: the originating job (if any) is unknown to the lookup,
        // which the terminal-job refusal treats as "nothing to refuse on".
        when(agentJobLookup.findById(any(UUID.class))).thenReturn(Optional.empty());
        eventPublisher = mock(ApplicationEventPublisher.class);

        executor =
                new AgentToolCallApprovalExecutor(
                        toolProviderProvider,
                        requestRepository,
                        notificationProvider,
                        sessionMemoryProvider,
                        agentJobLookupProvider,
                        com.agenticai.core.json.JsonMappers.plain(),
                        eventPublisher);
    }

    @AfterEach
    void cleanUpThreadLocals() {
        TenantContext.clear();
        ApprovalReplayGrant.clear();
    }

    private ToolCallback toolNamed(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    private ApprovalRequest envelopeRequest(String tenantId, String subject) {
        return envelopeRequest(tenantId, subject, "sess-1");
    }

    private ApprovalRequest envelopeRequest(String tenantId, String subject, String sessionId) {
        String payload =
                "{\"replay\":{\"version\":1,\"toolName\":\""
                        + TOOL_NAME
                        + "\","
                        + "\"agentId\":\""
                        + AGENT_ID
                        + "\",\"sessionId\":\""
                        + sessionId
                        + "\","
                        + (tenantId != null
                                ? "\"tenantId\":\""
                                        + tenantId
                                        + "\",\"subject\":\""
                                        + subject
                                        + "\""
                                : "\"tenantId\":null,\"subject\":null")
                        + "},\"arguments\":{\"to\":\"x\"}}";
        ApprovalRequest request =
                new ApprovalRequest(
                        ACTION_TYPE,
                        "Tool call: " + TOOL_NAME,
                        payload,
                        "agent:" + AGENT_ID + ":session:" + sessionId);
        return request;
    }

    @Test
    void happyPathInvokesToolWithRecordedArgumentsUnderRecordedTenantAndPersistsResult() {
        // The deciding admin's own context - must be restored untouched.
        TenantContext.set(TenantContext.of("caller-tenant", "admin-sub", Set.of()));

        ToolCallback callback = toolNamed(TOOL_NAME);
        AtomicReference<TenantContext> observedDuringCall = new AtomicReference<>();
        when(callback.call(anyString()))
                .thenAnswer(
                        invocation -> {
                            observedDuringCall.set(TenantContext.current().orElse(null));
                            return "{\"delivered\":true}";
                        });
        // Built BEFORE the when(): stubbing a fresh mock inside thenReturn's
        // argument list is Mockito's unfinished-stubbing trap.
        ToolCallback otherTool = toolNamed("other_tool");
        when(toolProvider.resolveToolCandidatesFor(eq(AGENT_ID), eq("sess-1"), eq("sess-1")))
                .thenReturn(List.of(otherTool, callback));

        ApprovalRequest request = envelopeRequest("tenant-a", "user-sub");
        executor.execute(request);

        // Invoked with the exact recorded arguments, under the RECORDED
        // tenant/subject - not the deciding admin's.
        verify(callback).call("{\"to\":\"x\"}");
        assertEquals("tenant-a", observedDuringCall.get().getTenantId());
        assertEquals("user-sub", observedDuringCall.get().getSubject());
        // Caller's context restored afterwards.
        assertEquals("caller-tenant", TenantContext.current().orElseThrow().getTenantId());
        // Result persisted explicitly (the gate service never persists on a
        // normal return - see persistExecutionResult's javadoc).
        assertEquals("{\"delivered\":true}", request.getExecutionResult());
        verify(requestRepository).save(request);
        // The originating human is told their approved action ran.
        verify(notificationService)
                .create(
                        eq("user-sub"),
                        eq(NotificationCategory.SYSTEM),
                        anyString(),
                        anyString(),
                        eq(NotificationSeverity.INFO),
                        isNull(),
                        isNull());
        // The single-use replay grant never outlives the invocation.
        assertFalse(ApprovalReplayGrant.consume(ACTION_TYPE));
        // (a) the event fires the instant executionResult is recorded - this
        // is the ONLY place a first-attempt success ever becomes durable,
        // since ApprovalRequestRecorder (core/approval) never sees this write.
        org.mockito.ArgumentCaptor<ApprovalRequestExecutionRecordedEvent> eventCaptor =
                org.mockito.ArgumentCaptor.forClass(ApprovalRequestExecutionRecordedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(request.getId(), eventCaptor.getValue().getRequestId());
        assertEquals(request.getRequestedBy(), eventCaptor.getValue().getRequestedBy());
    }

    /** The envelope as {@link #envelopeRequest} builds it, plus the recorded tool fingerprint. */
    private ApprovalRequest envelopeRequestWithDigest(String digest) {
        ApprovalRequest base = envelopeRequest("tenant-a", "user-sub");
        String payload =
                base.getPayloadJson()
                        .replace(
                                "\"sessionId\":",
                                "\"toolDigest\":\"" + digest + "\",\"sessionId\":");
        return new ApprovalRequest(
                ACTION_TYPE, "Tool call: " + TOOL_NAME, payload, base.getRequestedBy());
    }

    private ToolCallback toolWithDefinition(String name, String description) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                                .name(name)
                                .description(description)
                                .inputSchema("{}")
                                .build());
        return callback;
    }

    @Test
    void replayIsRefusedWhenTheToolBehindTheApprovedNameHasChanged() {
        String approvedDigest =
                com.agenticai.core.guardrails.ToolDefinitionDigest.of(
                        toolWithDefinition(TOOL_NAME, "Sends a report to the team")
                                .getToolDefinition());
        // Same name, new description: the MCP server behind it was swapped.
        ToolCallback swapped = toolWithDefinition(TOOL_NAME, "Sends a report to anyone, anywhere");
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(swapped));

        ApprovalRequest request = envelopeRequestWithDigest(approvedDigest);
        AgentToolReplayException thrown =
                assertThrows(AgentToolReplayException.class, () -> executor.execute(request));

        assertTrue(
                thrown.getMessage().contains("has changed since this request was approved"),
                thrown.getMessage());
        verify(swapped, never()).call(anyString());
        assertFalse(ApprovalReplayGrant.consume(ACTION_TYPE));
    }

    @Test
    void replayRunsWhenTheToolDefinitionIsUnchanged() {
        ToolCallback tool = toolWithDefinition(TOOL_NAME, "Sends a report to the team");
        when(tool.call(anyString())).thenReturn("{\"ok\":true}");
        String digest =
                com.agenticai.core.guardrails.ToolDefinitionDigest.of(tool.getToolDefinition());
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(tool));

        executor.execute(envelopeRequestWithDigest(digest));

        verify(tool).call("{\"to\":\"x\"}");
    }

    @Test
    void anEnvelopeRecordedBeforeFingerprintsExistedStillReplays() {
        ToolCallback tool = toolWithDefinition(TOOL_NAME, "whatever it says today");
        when(tool.call(anyString())).thenReturn("{\"ok\":true}");
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(tool));

        executor.execute(envelopeRequest("tenant-a", "user-sub"));

        verify(tool).call("{\"to\":\"x\"}");
    }

    @Test
    void tenantContextIsRestoredWhenTheToolThrows() {
        TenantContext.set(TenantContext.of("caller-tenant", "admin-sub", Set.of()));
        ToolCallback callback = toolNamed(TOOL_NAME);
        when(callback.call(anyString())).thenThrow(new RuntimeException("transport exploded"));
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(callback));

        ApprovalRequest request = envelopeRequest("tenant-a", "user-sub");
        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> executor.execute(request));

        // Rethrown unchanged so HumanApprovalGateService records the failure
        // exactly as for every other executor - and never marked retryable.
        assertEquals("transport exploded", thrown.getMessage());
        assertFalse(thrown instanceof RetryableActionFailure);
        assertEquals("caller-tenant", TenantContext.current().orElseThrow().getTenantId());
        assertNull(request.getExecutionResult());
        verify(requestRepository, never()).save(any());
        verify(notificationService)
                .create(
                        eq("user-sub"),
                        eq(NotificationCategory.SYSTEM),
                        anyString(),
                        anyString(),
                        eq(NotificationSeverity.ERROR),
                        isNull(),
                        isNull());
        assertFalse(ApprovalReplayGrant.consume(ACTION_TYPE));
        // No executionResult was ever recorded here - HumanApprovalGateService
        // records the failure itself, through ApprovalRequestRecorder, which
        // publishes its own event; this executor must not publish a second one.
        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * The M6 backstop: an approval that outlived its job must never fire the gated side effect. The
     * sweep rejects a terminalised job's PENDING requests itself, but a synchronous cancel of a
     * parked job (never seen by the sweep) and any failed best-effort rejection still leave an
     * approvable orphan - this refusal is what makes approving it harmless either way.
     */
    @Test
    void anApprovalWhoseJobIsAlreadyTerminalIsRefusedWithoutRunningTheTool() {
        UUID jobId = UUID.randomUUID();
        ToolCallback callback = toolNamed(TOOL_NAME);
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(callback));
        JobFacts terminalJob =
                new JobFacts(jobId, null, "job:" + jobId, null, "FAILED", true, "APPROVAL_TIMEOUT");
        when(agentJobLookup.findById(jobId)).thenReturn(Optional.of(terminalJob));

        ApprovalRequest request = envelopeRequest("tenant-a", "user-sub", "job:" + jobId);
        AgentToolReplayException thrown =
                assertThrows(AgentToolReplayException.class, () -> executor.execute(request));

        // The whole point: the non-idempotent tool never ran.
        verify(callback, never()).call(anyString());
        // Non-retryable - a terminal job will not un-terminate on a timer.
        assertFalse(thrown instanceof RetryableActionFailure);
        assertTrue(thrown.getMessage().contains(jobId.toString()));
        assertNull(request.getExecutionResult());
    }

    /** The refusal must not touch a live job's perfectly normal resume path. */
    @Test
    void anApprovalForAStillParkedJobReplaysNormally() {
        UUID jobId = UUID.randomUUID();
        ToolCallback callback = toolNamed(TOOL_NAME);
        when(callback.call(anyString())).thenReturn("done");
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(callback));
        JobFacts parkedJob =
                new JobFacts(jobId, null, "job:" + jobId, null, "AWAITING_APPROVAL", false, null);
        when(agentJobLookup.findById(jobId)).thenReturn(Optional.of(parkedJob));

        executor.execute(envelopeRequest("tenant-a", "user-sub", "job:" + jobId));

        verify(callback).call("{\"to\":\"x\"}");
    }

    @Test
    void unresolvableToolIsANonRetryableFailure() {
        ToolCallback someOtherTool = toolNamed("some_other_tool");
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(someOtherTool));

        ApprovalRequest request = envelopeRequest("tenant-a", "user-sub");
        AgentToolReplayException thrown =
                assertThrows(AgentToolReplayException.class, () -> executor.execute(request));

        // A disabled/removed tool won't fix itself on a timer, and blindly
        // re-running later is exactly what RetryableActionFailure's opt-in
        // contract exists to prevent here.
        assertFalse(thrown instanceof RetryableActionFailure);
        assertTrue(thrown.getMessage().contains(TOOL_NAME));
        verify(requestRepository, never()).save(any());
        // No context leaked either way.
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void legacyRawArgumentsPayloadReplaysViaTheRequestedByLabel() {
        ToolCallback callback = toolNamed("legacy_tool");
        when(callback.call(anyString())).thenReturn("ok");
        when(toolProvider.resolveToolCandidatesFor(eq(AGENT_ID), eq("sess-9"), eq("sess-9")))
                .thenReturn(List.of(callback));

        // Pre-envelope shape: payload IS the raw tool input, identity only
        // in the requestedBy label. No recorded tenant - the caller's
        // (correct) context is inherited unchanged.
        TenantContext.set(TenantContext.of("caller-tenant", "admin-sub", Set.of()));
        ApprovalRequest request =
                new ApprovalRequest(
                        "mcp_tool.legacy_tool",
                        "Tool call: legacy_tool",
                        "{\"a\":1}",
                        "agent:" + AGENT_ID + ":session:sess-9");
        executor.execute(request);

        verify(callback).call("{\"a\":1}");
        assertEquals("ok", request.getExecutionResult());
        verify(requestRepository).save(request);
        // No recorded subject -> broadcast to tenant-admins (null recipient).
        verify(notificationService)
                .create(
                        isNull(),
                        eq(NotificationCategory.SYSTEM),
                        anyString(),
                        anyString(),
                        eq(NotificationSeverity.INFO),
                        isNull(),
                        isNull());
    }

    // --- session-memory note (Phase 2: "approved tool result reaches the agent's next turn") ---

    @Test
    void successfulReplayAppendsAnAssistantNoteToTheOriginatingSessionsMemory() {
        ToolCallback callback = toolNamed(TOOL_NAME);
        when(callback.call(anyString())).thenReturn("{\"delivered\":true}");
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(callback));

        executor.execute(envelopeRequest("tenant-a", "user-sub"));

        org.mockito.ArgumentCaptor<ChatTurn> turnCaptor =
                org.mockito.ArgumentCaptor.forClass(ChatTurn.class);
        verify(sessionMemoryService).appendTurn(eq("sess-1"), turnCaptor.capture());
        ChatTurn note = turnCaptor.getValue();
        assertEquals(MessageRole.ASSISTANT, note.role());
        assertTrue(
                note.content().contains("approved action '" + TOOL_NAME + "' completed"),
                "note should say the action completed, got: " + note.content());
        assertTrue(
                note.content().contains("delivered"),
                "note should carry the (truncated) result, got: " + note.content());
    }

    @Test
    void failedReplayAppendsAFailureNoteSoTheAgentDoesNotSilentlyReAttempt() {
        ToolCallback callback = toolNamed(TOOL_NAME);
        when(callback.call(anyString())).thenThrow(new RuntimeException("transport exploded"));
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(callback));

        assertThrows(
                RuntimeException.class,
                () -> executor.execute(envelopeRequest("tenant-a", "user-sub")));

        org.mockito.ArgumentCaptor<ChatTurn> turnCaptor =
                org.mockito.ArgumentCaptor.forClass(ChatTurn.class);
        verify(sessionMemoryService).appendTurn(eq("sess-1"), turnCaptor.capture());
        assertTrue(
                turnCaptor.getValue().content().contains("failed"),
                "note should say the action failed, got: " + turnCaptor.getValue().content());
    }

    @Test
    void syntheticFallbackSessionIdNeverGetsAMemoryNote() {
        // No recorded sessionId in the envelope -> the executor synthesizes
        // "approval-replay:<id>" for tool resolution; there is no real
        // conversation behind that id, so nothing must be appended to it.
        ToolCallback callback = toolNamed(TOOL_NAME);
        when(callback.call(anyString())).thenReturn("ok");
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(callback));
        String payload =
                "{\"replay\":{\"version\":1,\"toolName\":\""
                        + TOOL_NAME
                        + "\","
                        + "\"agentId\":\""
                        + AGENT_ID
                        + "\",\"sessionId\":null},\"arguments\":{}}";
        ApprovalRequest request = new ApprovalRequest(ACTION_TYPE, "Tool call", payload, "someone");

        executor.execute(request);

        verify(sessionMemoryService, never()).appendTurn(anyString(), any());
    }

    @Test
    void memoryAppendFailureNeverFailsAnAlreadyCompletedApproval() {
        ToolCallback callback = toolNamed(TOOL_NAME);
        when(callback.call(anyString())).thenReturn("ok");
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(callback));
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(sessionMemoryService)
                .appendTurn(anyString(), any());

        ApprovalRequest request = envelopeRequest("tenant-a", "user-sub");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> executor.execute(request));

        assertEquals("ok", request.getExecutionResult());
        verify(requestRepository).save(request);
    }

    @Test
    void legacyRowWithoutAnAgentLabelCannotReplayAndSaysSo() {
        ApprovalRequest request =
                new ApprovalRequest(
                        "mcp_tool.legacy_tool",
                        "Tool call: legacy_tool",
                        "{\"a\":1}",
                        "someone-else");

        AgentToolReplayException thrown =
                assertThrows(AgentToolReplayException.class, () -> executor.execute(request));

        assertFalse(thrown instanceof RetryableActionFailure);
        verify(toolProvider, never()).resolveToolCandidatesFor(any(), anyString(), anyString());
    }
}
