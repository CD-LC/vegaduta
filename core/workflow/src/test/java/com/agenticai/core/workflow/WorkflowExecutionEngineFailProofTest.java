package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.devices.DeviceCommandService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.output.EmailOutputService;
import com.agenticai.core.workflow.output.GoogleDriveOutputService;
import com.agenticai.core.workflow.output.PdfOutputRenderer;
import com.agenticai.core.workflow.output.TeamsOutputService;
import com.agenticai.core.workflow.output.TelegramOutputService;
import com.agenticai.core.workflow.output.WhatsAppOutputService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * Fail-proof hardening pass on {@link WorkflowExecutionEngine}: before this, (1) a node that threw
 * before its own trace entry was recorded left the failure's ERROR trace entry pointing at whatever
 * node ran BEFORE it - the last one that already finished successfully - not the node that actually
 * failed, and (2) a single transient exception (e.g. an LLM API hiccup) from an Agent node failed
 * the entire run immediately, with zero retry anywhere in the synchronous (default) execution path.
 * Both are exercised here end-to-end through the real {@link WorkflowNodeExecutor}, not a mock of
 * it, so a regression in either fix would actually fail this suite.
 */
class WorkflowExecutionEngineFailProofTest {

    private WorkflowRunRepository runRepository;
    private OrchestratorService orchestratorService;
    private AgentChatService agentChatService;
    private AgentService agentService;
    private WorkflowExecutionEngine engine;

    private static final UUID AGENT_ID = UUID.randomUUID();

    /**
     * In the real app this is a Spring-managed bean with JavaTimeModule auto-registered by Spring
     * Boot's Jackson autoconfiguration - a plain {@code new ObjectMapper()} here cannot serialize
     * {@code NodeTraceEntry}'s {@code Instant} fields, and {@link
     * WorkflowExecutionEngine#writeTrace} silently swallows that failure and returns {@code "[]"}
     * (a deliberate best-effort posture for the real caller, but one that would otherwise make this
     * test's assertions observe an always-empty trace regardless of what actually happened).
     */
    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runRepository = mock(WorkflowRunRepository.class);
        orchestratorService = mock(OrchestratorService.class);
        agentChatService = mock(AgentChatService.class);
        // Left unstubbed deliberately: agentService.get(agentId) is only used
        // by executeAgentNode for an optional trace-transparency header, and
        // any RuntimeException it throws (including the NPE a mock's default
        // null AgentResponse would cause when its accessors are called) is
        // already caught and logged there without affecting the node's real
        // outcome - see WorkflowNodeExecutor#executeAgentNode.
        agentService = mock(AgentService.class);

        WorkflowNodeExecutor nodeExecutor =
                NodeExecutorTestFactory.create(
                        orchestratorService,
                        agentChatService,
                        agentService,
                        mock(ObjectProvider.class),
                        mock(WorkflowEventPublisher.class),
                        mock(EmailOutputService.class),
                        mock(TelegramOutputService.class),
                        mock(TeamsOutputService.class),
                        mock(WhatsAppOutputService.class),
                        mock(GoogleDriveOutputService.class),
                        mock(PdfOutputRenderer.class),
                        mock(ToolCallLogRepository.class),
                        mock(DeviceCommandService.class),
                        mock(AgentToolProvider.class),
                        mapper(),
                        mock(ObjectProvider.class),
                        mock(LlmResponseCacheService.class),
                        mock(QueryComplexityClassifier.class),
                        mock(FreeTierChatClientService.class),
                        mock(LlmGatewayService.class),
                        mock(ProviderCredentialResolver.class),
                        mock(TenantLimits.class),
                        mock(SubagentRunRepository.class),
                        mock(ObjectProvider.class),
                        // 24th arg: ObjectProvider<SidecarClient> for the MEDIA node. A bare
                        // mock returns null from getIfAvailable(), i.e. "no sidecar in this
                        // context" - which is what every test here wants.
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class));

        engine =
                new WorkflowExecutionEngine(
                        runRepository,
                        orchestratorService,
                        nodeExecutor,
                        mock(WorkflowEventPublisher.class),
                        mapper(),
                        mock(PlatformIssueService.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        // Mockito's default boolean return is false, i.e. "never
                        // cancelled" - exactly the right default for every test in this
                        // class, none of which exercise Stop Run.
                        mock(WorkflowRunCancellationService.class),
                        mock(WorkflowRunPauseService.class),
                        null,
                        null,
                        new com.agenticai.core.security.concurrent.ContextPropagatingExecutor(
                                "test-branch-", 16));
    }

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("config", config, "label", id));
    }

    private static WorkflowDefinition definitionWithId() throws ReflectiveOperationException {
        WorkflowDefinition definition =
                new WorkflowDefinition(
                        "Test Workflow",
                        "desc",
                        "{\"nodes\":[],\"edges\":[]}",
                        WorkflowStatus.ACTIVE);
        java.lang.reflect.Field idField = WorkflowDefinition.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(definition, UUID.randomUUID());
        return definition;
    }

    /**
     * trigger -> agent1 (succeeds) -> agent2 (throws every attempt). Before the fix, the ERROR
     * trace entry's nodeId was agent1's (the last node that finished) - it must now be agent2's,
     * the node that actually threw, with its real type/label carried through too.
     */
    @Test
    void failedNodeIsAttributedToTheNodeThatActuallyThrewNotThePreviousOne()
            throws ReflectiveOperationException {
        WorkflowNodeDto trigger = node("trigger1", "trigger", Map.of());
        WorkflowNodeDto agent1 = node("agent1", "agent", Map.of("agentId", AGENT_ID.toString()));
        WorkflowNodeDto agent2 = node("agent2", "agent", Map.of("agentId", AGENT_ID.toString()));
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(trigger, agent1, agent2),
                        List.of(
                                new WorkflowEdgeDto("e1", "trigger1", "agent1", null, null),
                                new WorkflowEdgeDto("e2", "agent1", "agent2", null, null)));

        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && java.util.Objects.equals(
                                                        r.userMessage(),
                                                        "Workflow started manually.")
                                                && r.turnRef() != null)))
                .thenReturn(Flux.just("first reply"));
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && java.util.Objects.equals(
                                                        r.userMessage(), "first reply")
                                                && r.turnRef() != null)))
                .thenThrow(new RuntimeException("LLM API unavailable"));

        WorkflowRun run = engine.execute(definitionWithId(), graph, null, "system:test");

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertTrue(
                run.getTraceJson().contains("\"nodeId\":\"agent2\""),
                "expected the ERROR trace entry to name agent2 (the node that threw), got: "
                        + run.getTraceJson());
        assertTrue(
                run.getTraceJson().contains("\"nodeType\":\"agent\""),
                "expected the ERROR trace entry to carry agent2's real node type, not the generic"
                        + " 'engine', got: "
                        + run.getTraceJson());
    }

    /**
     * An Agent node's chat() call fails twice, then succeeds on the third attempt - the run must
     * complete, not fail, proving the synchronous engine now retries a transient node failure
     * instead of dying on the first exception.
     */
    @Test
    void transientAgentNodeFailureIsRetriedAndTheRunStillCompletes()
            throws ReflectiveOperationException {
        WorkflowNodeDto trigger = node("trigger1", "trigger", Map.of());
        WorkflowNodeDto agent1 = node("agent1", "agent", Map.of("agentId", AGENT_ID.toString()));
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(trigger, agent1),
                        List.of(new WorkflowEdgeDto("e1", "trigger1", "agent1", null, null)));

        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.turnRef() != null)))
                .thenThrow(new RuntimeException("transient timeout"))
                .thenThrow(new RuntimeException("transient timeout"))
                .thenReturn(Flux.just("finally worked"));

        WorkflowRun run = engine.execute(definitionWithId(), graph, null, "system:test");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        verify(agentChatService, times(3))
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.turnRef() != null));
    }

    /**
     * An Output node that throws (not the soft ChannelException path executeOutputNode already
     * catches internally, a genuine bug like an NPE from a misconfigured destination service) must
     * NOT be retried - side-effecting sends are deliberately excluded from this engine's retry to
     * avoid risking a duplicate email/WhatsApp/Telegram send. The run still fails cleanly (this is
     * not a "swallow the error" test), it just must not have retried.
     */
    @Test
    void outputNodeFailureIsNeverRetried() throws ReflectiveOperationException {
        WorkflowNodeDto trigger = node("trigger1", "trigger", Map.of());
        WorkflowNodeDto output =
                node("output1", "output", Map.of("channel", "email", "emailTo", "ops@example.com"));
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(trigger, output),
                        List.of(new WorkflowEdgeDto("e1", "trigger1", "output1", null, null)));

        EmailOutputService throwingEmail = mock(EmailOutputService.class);
        when(throwingEmail.send(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        WorkflowNodeExecutor nodeExecutorWithThrowingEmail =
                NodeExecutorTestFactory.create(
                        orchestratorService,
                        agentChatService,
                        agentService,
                        mock(ObjectProvider.class),
                        mock(WorkflowEventPublisher.class),
                        throwingEmail,
                        mock(TelegramOutputService.class),
                        mock(TeamsOutputService.class),
                        mock(WhatsAppOutputService.class),
                        mock(GoogleDriveOutputService.class),
                        mock(PdfOutputRenderer.class),
                        mock(ToolCallLogRepository.class),
                        mock(DeviceCommandService.class),
                        mock(AgentToolProvider.class),
                        mapper(),
                        mock(ObjectProvider.class),
                        mock(LlmResponseCacheService.class),
                        mock(QueryComplexityClassifier.class),
                        mock(FreeTierChatClientService.class),
                        mock(LlmGatewayService.class),
                        mock(ProviderCredentialResolver.class),
                        mock(TenantLimits.class),
                        mock(SubagentRunRepository.class),
                        mock(ObjectProvider.class),
                        // 24th arg: ObjectProvider<SidecarClient> for the MEDIA node. A bare
                        // mock returns null from getIfAvailable(), i.e. "no sidecar in this
                        // context" - which is what every test here wants.
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class));
        WorkflowExecutionEngine engineWithThrowingEmail =
                new WorkflowExecutionEngine(
                        runRepository,
                        orchestratorService,
                        nodeExecutorWithThrowingEmail,
                        mock(WorkflowEventPublisher.class),
                        mapper(),
                        mock(PlatformIssueService.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(WorkflowRunCancellationService.class),
                        mock(WorkflowRunPauseService.class),
                        null,
                        null,
                        new com.agenticai.core.security.concurrent.ContextPropagatingExecutor(
                                "test-branch-", 16));

        WorkflowRun run =
                engineWithThrowingEmail.execute(definitionWithId(), graph, null, "system:test");

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        verify(throwingEmail, times(1)).send(any(), any(), any(), any(), any());
    }
}
