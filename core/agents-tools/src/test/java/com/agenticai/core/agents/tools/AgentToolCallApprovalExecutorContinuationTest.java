package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJobLookup;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.turn.ApprovalContinuation;
import com.agenticai.core.agents.turn.ApprovalContinuationPort;
import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestRepository;
import com.agenticai.core.approval.RetryableActionFailure;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link AgentToolCallApprovalExecutor}: the parked task is continued (or told of a denial) after a
 * decision.
 */
class AgentToolCallApprovalExecutorContinuationTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String TOOL_NAME = "send_message";
    private static final String ACTION_TYPE = "mcp_tool." + TOOL_NAME;

    private AgentToolProvider toolProvider;
    private ApprovalRequestRepository requestRepository;
    private NotificationService notificationService;
    private SessionMemoryService sessionMemoryService;
    private AgentJobLookup agentJobLookup;
    private ApplicationEventPublisher eventPublisher;
    private ApprovalContinuationPort launcher;
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
        launcher = mock(ApprovalContinuationPort.class);
        ObjectProvider<ApprovalContinuationPort> launcherProvider = mock(ObjectProvider.class);
        when(launcherProvider.getIfAvailable()).thenReturn(launcher);

        executor =
                new AgentToolCallApprovalExecutor(
                        toolProviderProvider,
                        requestRepository,
                        notificationProvider,
                        sessionMemoryProvider,
                        agentJobLookupProvider,
                        launcherProvider,
                        new org.springframework.beans.factory.support.StaticListableBeanFactory()
                                .getBeanProvider(ApprovalContinuationProperties.class),
                        new ObjectMapper(),
                        eventPublisher);
        // Most tests here use digest-less envelopes and are not about drift; the digest tests below
        // switch this off explicitly (the production default).
        executor.setAllowUndigested(true);
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

    // ------------------------------------------------------ the task is continued after a decision

    @Test
    void afterTheApprovedToolRanTheTaskIsContinuedWithItsFullResult() {
        ToolCallback callback = toolNamed(TOOL_NAME);
        String longResult = "{\"rows\":\"" + "r".repeat(5_000) + "\"}";
        when(callback.call(anyString())).thenReturn(longResult);
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(callback));
        ApprovalRequest request = envelopeRequest("tenant-a", "user-sub");

        executor.execute(request);

        verify(launcher).afterDecision(request, ApprovalContinuation.Outcome.COMPLETED, longResult);
    }

    @Test
    void aFailedReplayContinuesTheTaskWithTheFailure() {
        ToolCallback callback = toolNamed(TOOL_NAME);
        when(callback.call(anyString())).thenThrow(new RuntimeException("transport exploded"));
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(callback));
        ApprovalRequest request = envelopeRequest("tenant-a", "user-sub");

        assertThrows(RuntimeException.class, () -> executor.execute(request));

        verify(launcher)
                .afterDecision(request, ApprovalContinuation.Outcome.FAILED, "transport exploded");
    }

    @Test
    void aFailureThatWillBeRetriedIsNotReportedToTheAgentYet() {
        class Transient extends RuntimeException implements RetryableActionFailure {
            Transient() {
                super("timeout");
            }
        }
        ToolCallback callback = toolNamed(TOOL_NAME);
        when(callback.call(anyString())).thenThrow(new Transient());
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(callback));

        assertThrows(
                Transient.class, () -> executor.execute(envelopeRequest("tenant-a", "user-sub")));

        verify(launcher, never()).afterDecision(any(), any(), any());
    }

    @Test
    void aLauncherThatFailsNeverTurnsAnExecutedApprovalIntoAFailedOne() {
        ToolCallback callback = toolNamed(TOOL_NAME);
        when(callback.call(anyString())).thenReturn("ok");
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(callback));
        when(launcher.afterDecision(any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        ApprovalRequest request = envelopeRequest("tenant-a", "user-sub");

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> executor.execute(request));

        assertEquals("ok", request.getExecutionResult());
    }

    @Test
    void aDenialTellsTheConversationAndContinuesTheTaskWithTheReason() {
        TenantContext.set(TenantContext.of("caller-tenant", "admin-sub", Set.of()));
        ApprovalRequest request = envelopeRequest("tenant-a", "user-sub");

        executor.onRejected(request, "admin-sub", "Not before the audit");

        verify(launcher)
                .afterDecision(
                        request, ApprovalContinuation.Outcome.DENIED, "Not before the audit");
        verify(sessionMemoryService)
                .appendTurn(
                        anyString(),
                        org.mockito.ArgumentMatchers.argThat(
                                turn ->
                                        ChatTurn.isToolRecordOnly(turn)
                                                && turn.tools().entries().get(0).status()
                                                        == com.agenticai.core.agents.turn.ToolLedger
                                                                .Status.NOT_RUN
                                                && turn.tools()
                                                        .entries()
                                                        .get(0)
                                                        .result()
                                                        .contains("DENIED by admin-sub")
                                                && turn.tools()
                                                        .entries()
                                                        .get(0)
                                                        .result()
                                                        .contains("Not before the audit")
                                                && turn.tools()
                                                        .entries()
                                                        .get(0)
                                                        .result()
                                                        .contains("did not run")
                                                && turn.tools()
                                                        .entries()
                                                        .get(0)
                                                        .approval()
                                                        .contains("admin-sub")));
        // The tool never ran, and the decider's own context is untouched.
        verify(toolProvider, never()).resolveToolCandidatesFor(any(), anyString(), anyString());
        assertEquals("caller-tenant", TenantContext.current().orElseThrow().getTenantId());
    }

    @Test
    void aDenialWithoutAReasonSaysSo() {
        ApprovalRequest request = envelopeRequest("tenant-a", "user-sub");

        executor.onRejected(request, "admin-sub", null);

        verify(launcher)
                .afterDecision(request, ApprovalContinuation.Outcome.DENIED, "no reason was given");
    }

    @Test
    void aDenialOfARowWithoutAUsableEnvelopeIsQuietlyIgnored() {
        ApprovalRequest legacyUnidentified = new ApprovalRequest(ACTION_TYPE, "s", "{}", "someone");

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> executor.onRejected(legacyUnidentified, "admin-sub", "no"));

        verify(launcher, never()).afterDecision(any(), any(), any());
    }
}
