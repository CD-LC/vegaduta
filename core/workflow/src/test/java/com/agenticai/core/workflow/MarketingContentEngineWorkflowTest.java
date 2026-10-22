package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.agenticai.core.approval.ApprovalEvaluation;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Engine-level proof for the "Marketing Content Engine" pattern seeded as a community template (see
 * {@code V219__marketing_content_engine_agent_templates.sql} / {@code
 * V220__marketing_content_engine_workflow_template.sql}) and documented in {@code
 * recipes-growth-sales.tsx}'s {@code mkt-content-engine} recipe: one content-lead agent briefs,
 * three writer agents draft in {@code PARALLEL}, and a human {@code APPROVAL} gate holds the run
 * before anything would be published. {@code SUPERVISOR} is deliberately NOT used for the content
 * lead - that node type is a branch router ({@link WorkflowNodeType}'s javadoc), not a fan-out
 * coordinator; a plain {@code AGENT} node briefing straight into a {@code PARALLEL} node is this
 * platform's actual shape for "one agent hands off to several running at once", the same shape
 * {@code V164}'s "Parallel Research Aggregator" template already uses (3 branches converging on one
 * next node via 3 separate edges, not a dedicated join node - see that node type's own "continues
 * from whichever branch resolves last" behavior).
 *
 * <p>Harness copied from {@link WorkflowExecutionEngineFanOutTest} - real {@link
 * WorkflowNodeExecutor}/{@link WorkflowExecutionEngine}, every collaborator mocked, no Spring
 * context, no DB.
 */
class MarketingContentEngineWorkflowTest {

    private WorkflowRunRepository runRepository;
    private OrchestratorService orchestratorService;
    private AgentChatService agentChatService;
    private WorkflowExecutionEngine engine;

    private static final UUID SUPERVISOR_AGENT_ID = UUID.randomUUID();
    private static final UUID LONGFORM_WRITER_ID = UUID.randomUUID();
    private static final UUID SHORTFORM_WRITER_ID = UUID.randomUUID();
    private static final UUID VISUAL_WRITER_ID = UUID.randomUUID();

    private static ObjectMapper mapper() {
        return com.agenticai.core.json.JsonMappers.plain();
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
                        mock(WorkflowRunCancellationService.class),
                        mock(WorkflowRunPauseService.class),
                        null,
                        null,
                        new com.agenticai.core.security.concurrent.ContextPropagatingExecutor(
                                "test-branch-", 16));

        stubWriter(SUPERVISOR_AGENT_ID, "BRIEF: angle=customer-quote, long-form+short-form+visual");
        stubWriter(LONGFORM_WRITER_ID, "LONGFORM_DRAFT: ...");
        stubWriter(SHORTFORM_WRITER_ID, "SHORTFORM_DRAFT: ...");
        stubWriter(VISUAL_WRITER_ID, "VISUAL_DRAFT: ...");
    }

    private void stubWriter(UUID agentId, String reply) {
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), agentId)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.turnRef() != null)))
                .thenReturn(Flux.just(reply));
    }

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("config", config, "label", id));
    }

    private static WorkflowDefinition definitionWithId() throws ReflectiveOperationException {
        WorkflowDefinition definition =
                new WorkflowDefinition(
                        "Marketing Content Engine",
                        "desc",
                        "{\"nodes\":[],\"edges\":[]}",
                        WorkflowStatus.ACTIVE);
        java.lang.reflect.Field idField = WorkflowDefinition.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(definition, UUID.randomUUID());
        return definition;
    }

    /**
     * trigger -&gt; agent(content lead) -&gt; parallel -&gt; {agent(long-form), agent(short-form),
     * agent(visual)} -&gt; approval -&gt; output(log). Same 3-branches-converge-via-3-edges shape
     * as V164's "Parallel Research Aggregator" template, with an APPROVAL node standing in for that
     * template's aggregator-agent step.
     */
    private static WorkflowGraphDto contentEngineGraph() {
        return new WorkflowGraphDto(
                List.of(
                        node("n1", "trigger", Map.of()),
                        node("n2", "agent", Map.of("agentId", SUPERVISOR_AGENT_ID.toString())),
                        node("n3", "parallel", Map.of()),
                        node("n4", "agent", Map.of("agentId", LONGFORM_WRITER_ID.toString())),
                        node("n5", "agent", Map.of("agentId", SHORTFORM_WRITER_ID.toString())),
                        node("n6", "agent", Map.of("agentId", VISUAL_WRITER_ID.toString())),
                        node("n7", "approval", Map.of()),
                        node("n8", "output", Map.of("channel", "log"))),
                List.of(
                        new WorkflowEdgeDto("e1", "n1", "n2", null, null),
                        new WorkflowEdgeDto("e2", "n2", "n3", null, null),
                        new WorkflowEdgeDto("e3", "n3", "n4", null, null),
                        new WorkflowEdgeDto("e4", "n3", "n5", null, null),
                        new WorkflowEdgeDto("e5", "n3", "n6", null, null),
                        new WorkflowEdgeDto("e6", "n4", "n7", null, null),
                        new WorkflowEdgeDto("e7", "n5", "n7", null, null),
                        new WorkflowEdgeDto("e8", "n6", "n7", null, null),
                        new WorkflowEdgeDto("e9", "n7", "n8", null, null)));
    }

    private void verifyAllFourAgentsWereCalledExactlyOnce() {
        verify(agentChatService, times(1))
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), SUPERVISOR_AGENT_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.turnRef() != null));
        verify(agentChatService, times(1))
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), LONGFORM_WRITER_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.turnRef() != null));
        verify(agentChatService, times(1))
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), SHORTFORM_WRITER_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.turnRef() != null));
        verify(agentChatService, times(1))
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), VISUAL_WRITER_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.turnRef() != null));
        verify(agentChatService, times(4))
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.turnRef() != null));
    }

    /**
     * The "completed task" proof: with the approval policy auto-approving (the same {@code
     * evaluation.approved()==true} path a tenant's default, non-{@code ALWAYS_GATE} policy would
     * take), the brief runs, all three writers run concurrently, the gate passes, and the run
     * reaches {@code COMPLETED} in one call - no DB, no waiting.
     */
    @Test
    void contentEngineCompletesEndToEndWhenApprovalPolicyAutoApproves()
            throws ReflectiveOperationException {
        when(orchestratorService.authorizeSensitiveNode(
                        anyString(),
                        anyString(),
                        any(WorkflowNodeDto.class),
                        anyString(),
                        anyString(),
                        any()))
                .thenReturn(new ApprovalEvaluation(true, null));

        WorkflowRun run =
                engine.execute(
                        definitionWithId(),
                        contentEngineGraph(),
                        "Shipped feature: bulk WhatsApp order confirmations.",
                        "system:test");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        verifyAllFourAgentsWereCalledExactlyOnce();
    }

    /**
     * The governance proof this recipe is actually built for: a human must approve before anything
     * downstream of the writers happens. All four agent calls (the brief + the three concurrent
     * drafts) still happen - the gate sits AFTER the parallel fan-out, not before it - but the run
     * halts at {@code AWAITING_APPROVAL} instead of reaching the output node.
     */
    @Test
    void contentEngineHaltsForApprovalAfterAllThreeWritersFinish()
            throws ReflectiveOperationException {
        when(orchestratorService.authorizeSensitiveNode(
                        anyString(),
                        anyString(),
                        any(WorkflowNodeDto.class),
                        anyString(),
                        anyString(),
                        any()))
                .thenReturn(new ApprovalEvaluation(false, UUID.randomUUID()));

        WorkflowRun run =
                engine.execute(
                        definitionWithId(),
                        contentEngineGraph(),
                        "Shipped feature: bulk WhatsApp order confirmations.",
                        "system:test");

        assertEquals(WorkflowRunStatus.AWAITING_APPROVAL, run.getStatus());
        verifyAllFourAgentsWereCalledExactlyOnce();
    }
}
