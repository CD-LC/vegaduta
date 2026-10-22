package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Run-level fields on the synchronous engine: the max-steps ERROR trace row names the node about to
 * run (SE-53), and the operator comment is stored on the run, never merged into the node input
 * (SE-14).
 */
class WorkflowExecutionEngineRunFieldsTest {

    private WorkflowRunRepository runRepository;
    private OrchestratorService orchestratorService;
    private AgentChatService agentChatService;
    private WorkflowExecutionEngine engine;

    private static final UUID AGENT_ID = UUID.randomUUID();

    /**
     * See WorkflowExecutionEngineFailProofTest#mapper - a bare ObjectMapper can't serialize
     * NodeTraceEntry's Instants.
     */
    private static ObjectMapper mapper() {
        return com.agenticai.core.json.JsonMappers.withJavaTime();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runRepository = mock(WorkflowRunRepository.class);
        orchestratorService = mock(OrchestratorService.class);
        agentChatService = mock(AgentChatService.class);

        WorkflowNodeExecutor nodeExecutor =
                NodeExecutorTestFactory.create(
                        orchestratorService,
                        agentChatService,
                        mock(AgentService.class),
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
                        // cancelled" - right for every test here (the per-item
                        // cancellation path needs a live flag flip mid-run, which this
                        // synchronous harness can't stage without real cross-thread
                        // orchestration; it's exercised implicitly by the flag check
                        // being the worker lambda's first statement).
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

    /** trigger -> a1 -> a2 (two agent nodes). */
    private static WorkflowGraphDto threeNodeGraph() {
        return new WorkflowGraphDto(
                List.of(
                        node("trigger1", "trigger", Map.of()),
                        node("a1", "agent", Map.of("agentId", AGENT_ID.toString())),
                        node("a2", "agent", Map.of("agentId", AGENT_ID.toString()))),
                List.of(
                        new WorkflowEdgeDto("e1", "trigger1", "a1", null, null),
                        new WorkflowEdgeDto("e2", "a1", "a2", null, null)));
    }

    private void agentEchoes() {
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.turnRef() != null)))
                .thenAnswer(invocation -> Flux.just("reply"));
    }

    @Test
    void maxStepsErrorRowNamesTheNodeThatWasAboutToRunNotThePreviousOne()
            throws ReflectiveOperationException {
        agentEchoes();
        WorkflowDefinition definition = definitionWithId();
        definition.setExecutionLimits(2, 30);

        WorkflowRun run = engine.execute(definition, threeNodeGraph(), "start", "system:test");

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        JsonNode trace = readTrace(run);
        assertEquals(3, trace.size(), run.getTraceJson());
        JsonNode last = trace.get(2);
        assertEquals("ERROR", last.get("status").asString());
        assertEquals(
                "a2", last.get("nodeId").asString(), "the row must carry the node about to run");
        assertEquals("a1", trace.get(1).get("nodeId").asString());
        assertEquals(
                "OK",
                trace.get(1).get("status").asString(),
                "the previous node's own row is not duplicated");
    }

    @Test
    void theRunCommentIsStoredOnTheRunAndNeverReachesTheNodeInput()
            throws ReflectiveOperationException {
        agentEchoes();
        WorkflowDefinition definition = definitionWithId();

        WorkflowRun run =
                engine.execute(
                        definition,
                        threeNodeGraph(),
                        null,
                        "refund please",
                        "system:test",
                        null,
                        List.of(),
                        RunMode.PRODUCTION,
                        "  handle with care  ");

        assertEquals("handle with care", run.getComment());
        assertTrue(
                !run.getTraceJson().contains("handle with care"),
                "the comment must not appear in any node input: " + run.getTraceJson());
        assertEquals(
                "handle with care",
                com.agenticai.core.workflow.dto.WorkflowRunResponse.from(run, mapper()).comment());
    }

    @Test
    void skippedNodesAreCountedAsRunWarnings() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), "wf", "me");
        run.complete(
                WorkflowRunStatus.COMPLETED,
                "[{\"nodeId\":\"n1\",\"status\":\"OK\"},{\"nodeId\":\"n2\",\"status\":\"SKIPPED\"},"
                        + "{\"nodeId\":\"n3\",\"status\":\"SKIPPED\"}]",
                null,
                null,
                null);

        var response = com.agenticai.core.workflow.dto.WorkflowRunResponse.from(run, mapper());

        assertEquals(WorkflowRunStatus.COMPLETED, response.status());
        assertEquals(2, response.warningCount());
    }

    private JsonNode readTrace(WorkflowRun run) {
        try {
            return mapper().readTree(run.getTraceJson());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
