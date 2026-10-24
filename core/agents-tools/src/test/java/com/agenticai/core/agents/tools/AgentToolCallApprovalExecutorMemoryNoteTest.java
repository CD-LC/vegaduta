package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.e2e.InMemoryRedis;
import com.agenticai.core.agents.job.AgentJobLookup;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.SessionSummaryService;
import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.agents.turn.ToolLedger;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestRepository;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PlatformSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Where the executor writes the "[approved action ... completed]" note. A mock-based test used to
 * assert {@code appendTurn(eq("sess-1"), ...)} and so enshrined the bug: the note went under the RAW
 * client session id while every turn reads its history under the SEC-004 scoped id. These tests run
 * the real {@link SessionMemoryService} (over an in-memory Redis) and read the history back the way
 * the next turn does.
 */
class AgentToolCallApprovalExecutorMemoryNoteTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String TOOL = "send_invoice";
    private static final String RAW_SESSION = "sess-raw-1";
    private static final String TENANT = "tenant-a";
    private static final String SUBJECT = "user-1";

    private InMemoryRedis redis;
    private SessionMemoryService memory;
    private NotificationService notifications;
    private AgentToolProvider toolProvider;
    private AgentToolCallApprovalExecutor executor;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = new InMemoryRedis();
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        memory =
                new SessionMemoryService(
                        redis.template(),
                        new ObjectMapper().findAndRegisterModules(),
                        settings,
                        new StaticListableBeanFactory().getBeanProvider(SessionSummaryService.class),
                        24,
                        40,
                        32_000,
                        8_000,
                        0);
        notifications = mock(NotificationService.class);
        toolProvider = mock(AgentToolProvider.class);
        ObjectProvider<AgentToolProvider> toolProviderProvider = mock(ObjectProvider.class);
        when(toolProviderProvider.getObject()).thenReturn(toolProvider);
        ObjectProvider<NotificationService> notificationProvider = mock(ObjectProvider.class);
        when(notificationProvider.getIfAvailable()).thenReturn(notifications);
        ObjectProvider<SessionMemoryService> memoryProvider = mock(ObjectProvider.class);
        when(memoryProvider.getIfAvailable()).thenReturn(memory);
        ObjectProvider<AgentJobLookup> noJobs = mock(ObjectProvider.class);
        executor =
                new AgentToolCallApprovalExecutor(
                        toolProviderProvider,
                        mock(ApprovalRequestRepository.class),
                        notificationProvider,
                        memoryProvider,
                        noJobs,
                        mock(ObjectProvider.class),
                        // No ApprovalContinuationProperties bean: the shipped wording applies, as in production defaults.
                        new StaticListableBeanFactory().getBeanProvider(ApprovalContinuationProperties.class),
                        new ObjectMapper(),
                        mock(ApplicationEventPublisher.class));
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        ApprovalReplayGrant.clear();
    }

    private void toolReturns(String output) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(TOOL);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString())).thenReturn(output);
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(callback));
    }

    private void toolFails(String message) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(TOOL);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString())).thenThrow(new RuntimeException(message));
        when(toolProvider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(callback));
    }

    private ApprovalRequest request(String replayExtras) {
        String payload =
                "{\"replay\":{\"version\":1,\"toolName\":\""
                        + TOOL
                        + "\",\"agentId\":\""
                        + AGENT_ID
                        + "\",\"sessionId\":\""
                        + RAW_SESSION
                        + "\""
                        + replayExtras
                        + "},\"arguments\":{\"to\":\"x\"}}";
        return new ApprovalRequest(
                "mcp_tool." + TOOL,
                "Tool call: " + TOOL,
                payload,
                "agent:" + AGENT_ID + ":session:" + RAW_SESSION);
    }

    private static String scopedKey() {
        return SessionMemoryService.scopedSessionId(TENANT, AGENT_ID, SUBJECT, RAW_SESSION);
    }

    private static boolean hasNote(List<ChatTurn> turns, String fragment) {
        return turns.stream().anyMatch(t -> t.content() != null && t.content().contains(fragment));
    }

    @Test
    void theNoteGoesToTheScopedHistoryTheEnvelopeNamesAndNotToTheRawSessionId() {
        toolReturns("CONF-9042");
        String scoped = "c1:from-the-envelope";

        executor.execute(
                request(
                        ",\"memorySessionId\":\""
                                + scoped
                                + "\",\"tenantId\":\""
                                + TENANT
                                + "\",\"subject\":\""
                                + SUBJECT
                                + "\""));

        assertTrue(
                hasNote(memory.getHistory(scoped), "[approved action '" + TOOL + "' completed"),
                "the note must be in the history the next turn reads; got "
                        + memory.getHistory(scoped));
        assertTrue(hasNote(memory.getHistory(scoped), "CONF-9042"), "the note carries the output");
        assertEquals(
                List.of(),
                memory.getHistory(RAW_SESSION),
                "nothing may be written under the raw client session id");
    }

    @Test
    void theOutcomeReachesTheModelAsAToolRecordButNeverTheTranscriptAsAnAssistantMessage() {
        toolReturns("[UNTRUSTED CONTENT from tool 'send_invoice' <<<UNTRUSTED_DATA id=7>>> CONF-77 ]");
        String scoped = "c1:from-the-envelope";

        executor.execute(
                request(
                        ",\"memorySessionId\":\""
                                + scoped
                                + "\",\"tenantId\":\""
                                + TENANT
                                + "\",\"subject\":\""
                                + SUBJECT
                                + "\""));

        // What is stored: an assistant turn with no text and one OK entry for the tool.
        List<ChatTurn> stored = memory.getFullHistory(scoped);
        assertEquals(1, stored.size());
        ChatTurn turn = stored.get(0);
        assertTrue(ChatTurn.isToolRecordOnly(turn), "stored as a tool record, not as assistant text: " + turn);
        assertEquals("", turn.content());
        ToolLedger.Entry entry = turn.tools().entries().get(0);
        assertEquals(TOOL, entry.tool());
        assertEquals(ToolLedger.Status.OK, entry.status());
        assertTrue(entry.arguments().contains("\"to\""), entry.arguments());
        assertTrue(entry.result().contains("CONF-77"), entry.result());

        // The model's view: the record folded into the text, with the configured note and the output.
        List<ChatTurn> modelView = memory.getHistory(scoped);
        assertTrue(hasNote(modelView, "[approved action '" + TOOL + "' completed"), modelView.toString());
        assertTrue(hasNote(modelView, "CONF-77"), modelView.toString());
        assertTrue(hasNote(modelView, TOOL + "("), "rendered as a tool record line: " + modelView);
    }

    @Test
    void aFailureNoteAlsoGoesToTheScopedHistory() {
        toolFails("transport exploded");
        String scoped = "c1:from-the-envelope";

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () ->
                        executor.execute(
                                request(",\"memorySessionId\":\"" + scoped + "\"")));

        assertTrue(hasNote(memory.getHistory(scoped), "[approved action '" + TOOL + "' failed"));
        assertEquals(List.of(), memory.getHistory(RAW_SESSION));
    }

    @Test
    void anEnvelopeRecordedBeforeTheScopedIdExistedFallsBackToDerivingItFromTenantAndSubject() {
        toolReturns("CONF-1");

        executor.execute(
                request(",\"tenantId\":\"" + TENANT + "\",\"subject\":\"" + SUBJECT + "\""));

        assertTrue(
                hasNote(memory.getHistory(scopedKey()), "[approved action '" + TOOL + "' completed"),
                "legacy envelope: the id is derived the way ChatTurnPreflight derives it; keys: "
                        + redis.listKeys());
        assertEquals(List.of(), memory.getHistory(RAW_SESSION));
    }

    @Test
    void whenNoScopedIdCanBeFoundNothingIsWrittenUnderTheRawIdAndTheApproverIsWarned() {
        toolReturns("CONF-2");

        // No memorySessionId, and no tenant/subject to derive one from.
        executor.execute(request(""));

        assertEquals(List.of(), memory.getHistory(RAW_SESSION), "never fall back to the raw id");
        assertEquals(List.of(), redis.listKeys());
        verify(notifications)
                .create(
                        any(),
                        eq(NotificationCategory.SYSTEM),
                        anyString(),
                        org.mockito.ArgumentMatchers.contains("CONF-2"),
                        eq(NotificationSeverity.WARNING),
                        isNull(),
                        isNull());
    }

    @Test
    void aMemoryWriteFailureStillCompletesTheApprovalButIsVisibleToTheApprover() {
        toolReturns("CONF-3");
        memory = mock(SessionMemoryService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(memory)
                .appendTurn(anyString(), any());
        @SuppressWarnings("unchecked")
        ObjectProvider<SessionMemoryService> failing = mock(ObjectProvider.class);
        when(failing.getIfAvailable()).thenReturn(memory);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentToolProvider> toolProviderProvider = mock(ObjectProvider.class);
        when(toolProviderProvider.getObject()).thenReturn(toolProvider);
        @SuppressWarnings("unchecked")
        ObjectProvider<NotificationService> notificationProvider = mock(ObjectProvider.class);
        when(notificationProvider.getIfAvailable()).thenReturn(notifications);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentJobLookup> noJobs = mock(ObjectProvider.class);
        AgentToolCallApprovalExecutor failingExecutor =
                new AgentToolCallApprovalExecutor(
                        toolProviderProvider,
                        mock(ApprovalRequestRepository.class),
                        notificationProvider,
                        failing,
                        noJobs,
                        mock(ObjectProvider.class),
                        // No ApprovalContinuationProperties bean: the shipped wording applies, as in production defaults.
                        new StaticListableBeanFactory().getBeanProvider(ApprovalContinuationProperties.class),
                        new ObjectMapper(),
                        mock(ApplicationEventPublisher.class));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> failingExecutor.execute(request(",\"memorySessionId\":\"c1:x\"")));

        verify(notifications)
                .create(
                        any(),
                        eq(NotificationCategory.SYSTEM),
                        anyString(),
                        org.mockito.ArgumentMatchers.contains("redis down"),
                        eq(NotificationSeverity.WARNING),
                        isNull(),
                        isNull());
    }
}
