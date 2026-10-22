package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.devices.DeviceCommandService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.expression.NodeOutput;
import com.agenticai.core.workflow.output.EmailOutputService;
import com.agenticai.core.workflow.output.GoogleDriveOutputService;
import com.agenticai.core.workflow.output.PdfOutputRenderer;
import com.agenticai.core.workflow.output.TeamsOutputService;
import com.agenticai.core.workflow.output.TelegramOutputService;
import com.agenticai.core.workflow.output.WhatsAppOutputService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * The pin hook in {@link WorkflowNodeExecutor#executeNode}: a pinned node is replaced by its pin
 * ONLY when the bound scope says the run is a draft/test run. A production run binds no mode, so
 * the pin store is not even consulted - that is the property that keeps a stray pin from altering a
 * webhook/cron/channel run.
 */
class WorkflowNodeExecutorPinTest {

    private static final UUID WF = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();

    private WorkflowPinStore pinStore;
    private OrchestratorService orchestrator;
    private List<WorkflowRunNodeOutput> recorded;
    private WorkflowNodeExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        WorkflowNodePinRepository repo = PinTestSupport.pinRepository();
        pinStore = spy(new WorkflowPinStore(repo));
        orchestrator = mock(OrchestratorService.class);
        recorded = new ArrayList<>();
        ObjectMapper mapper = com.agenticai.core.json.JsonMappers.plain();
        RunDataStore runData = new RunDataStore(PinTestSupport.runOutputRepository(recorded));
        WorkflowExpressionResolver resolver =
                new WorkflowExpressionResolver(
                        runData, mock(WorkflowRunRepository.class), mapper, true, false);
        executor = newExecutor(orchestrator, mapper);
        executor.setExpressions(resolver);
        executor.setPinStore(pinStore);
    }

    @AfterEach
    void clearScope() {
        ExpressionScopeContext.clear();
    }

    @SuppressWarnings("unchecked")
    static WorkflowNodeExecutor newExecutor(OrchestratorService orchestrator, ObjectMapper mapper) {
        return NodeExecutorTestFactory.create(
                orchestrator,
                mock(AgentChatService.class),
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
                mapper,
                mock(ObjectProvider.class),
                mock(LlmResponseCacheService.class),
                mock(QueryComplexityClassifier.class),
                mock(FreeTierChatClientService.class),
                mock(LlmGatewayService.class),
                mock(ProviderCredentialResolver.class),
                mock(TenantLimits.class),
                mock(SubagentRunRepository.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class));
    }

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(
                id, type, Map.of(), Map.of("config", config, "label", id.toUpperCase()));
    }

    private WorkflowNodeExecutor.NodeExecutionResult run(
            WorkflowNodeDto node, String input, ExpressionScope scope) {
        ExpressionScopeContext.bind(scope);
        try {
            return executor.executeNode(WF.toString(), "wf", RUN.toString(), node, input, "tester");
        } finally {
            ExpressionScopeContext.clear();
        }
    }

    private void pin(String nodeId, String data) {
        pinStore.save(WF, nodeId, data, "manual", null, "ann", null);
    }

    private static ExpressionScope draft() {
        return ExpressionScope.forMode(null, RunMode.DRAFT);
    }

    @Test
    void aProductionRunExecutesAPinnedNodeAndNeverConsultsThePinStore() {
        pin("n2", "PINNED");
        org.mockito.Mockito.clearInvocations(pinStore);
        WorkflowNodeDto upper = node("n2", "code", Map.of("operation", "uppercase"));

        // The production binding: no scope at all (what WorkflowExecutionEngine binds when the run
        // is PRODUCTION) ...
        WorkflowNodeExecutor.NodeExecutionResult viaNull =
                run(upper, "hello", ExpressionScope.forMode(null, RunMode.PRODUCTION));
        // ... and an iteration scope with no mode (a production loop body).
        WorkflowNodeExecutor.NodeExecutionResult viaIteration =
                run(
                        upper,
                        "hello",
                        ExpressionScope.forMode(
                                ExpressionScope.iteration("hello", 0, List.of("hello")),
                                RunMode.PRODUCTION));

        assertEquals("OK", viaNull.status());
        assertEquals("HELLO", viaNull.output());
        assertEquals("HELLO", viaIteration.output());
        verify(pinStore, never()).find(any(), anyString());
    }

    @Test
    void aDraftRunServesThePinInsteadOfExecutingTheNode() {
        pin("n2", "pinned text");

        WorkflowNodeExecutor.NodeExecutionResult r =
                run(node("n2", "code", Map.of("operation", "uppercase")), "hello", draft());

        assertEquals("PINNED", r.status());
        assertEquals("pinned text", r.output(), "the pin, not the uppercase of the input");
        assertNull(r.haltRun(), "PINNED must continue the run like a success");
        assertNull(r.branch());
        assertTrue(r.summary().contains("pinned"), r.summary());
    }

    @Test
    void aDraftRunDoesNotExecuteAPinnedSideEffectNode() {
        pin("n3", "{\"status\":200}");
        // An http node with no executor wired would SKIP or fail if it ran; pinned, it is never
        // dispatched.
        WorkflowNodeExecutor.NodeExecutionResult r =
                run(
                        node(
                                "n3",
                                "http",
                                Map.of("method", "POST", "url", "https://example.com/charge")),
                        "x",
                        draft());
        assertEquals("PINNED", r.status());
        assertEquals("{\"status\":200}", r.output());
    }

    @Test
    void aDraftRunExecutesNodesThatHaveNoPin() {
        pin("other", "x");
        WorkflowNodeExecutor.NodeExecutionResult r =
                run(node("n2", "code", Map.of("operation", "uppercase")), "hello", draft());
        assertEquals("OK", r.status());
        assertEquals("HELLO", r.output());
    }

    @Test
    void theTestModeAlsoServesPins() {
        pin("n2", "from pin");
        assertEquals(
                "PINNED",
                run(
                                node("n2", "code", Map.of("operation", "uppercase")),
                                "x",
                                ExpressionScope.forMode(null, RunMode.TEST))
                        .status());
    }

    @Test
    void aStepTestRunsTheNodeUnderTestForRealEvenIfItIsPinned() {
        pin("n2", "from pin");
        // The step test's scope carries the caller-supplied upstream data ("supplied"), which is
        // what excludes it.
        ExpressionScope stepTest =
                ExpressionScope.test(Map.of("id:up", new NodeOutput("up", "Up", "{}", "OK", null)));
        WorkflowNodeExecutor.NodeExecutionResult r =
                run(node("n2", "code", Map.of("operation", "uppercase")), "hello", stepTest);
        assertEquals("OK", r.status());
        assertEquals("HELLO", r.output());
    }

    @Test
    void routingNodesAlwaysExecuteBecauseAPinCarriesNoRoutingDecision() {
        pin("c1", "whatever");
        WorkflowNodeExecutor.NodeExecutionResult r =
                run(
                        node(
                                "c1",
                                "condition",
                                Map.of("mode", "expression", "expression", "$json.ok == true")),
                        "{\"ok\":true}",
                        draft());
        assertEquals("OK", r.status());
        assertEquals("true", r.branch());
    }

    @Test
    void thePinWinsOverAnApprovalGateInDraftButNotInProduction() {
        pin("n2", "pinned");
        WorkflowNodeDto gated =
                node("n2", "code", Map.of("operation", "uppercase", "requiresApproval", true));
        when(orchestrator.authorizeSensitiveNode(
                        anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(new ApprovalEvaluation(false, UUID.randomUUID()));

        WorkflowNodeExecutor.NodeExecutionResult draftResult = run(gated, "hello", draft());
        assertEquals("PINNED", draftResult.status(), "no approval wait in a draft run");
        verify(orchestrator, never())
                .authorizeSensitiveNode(anyString(), anyString(), any(), anyString(), any(), any());

        WorkflowNodeExecutor.NodeExecutionResult production = run(gated, "hello", null);
        assertEquals(
                WorkflowRunStatus.AWAITING_APPROVAL,
                production.haltRun(),
                "production still waits for a human");
    }

    @Test
    void theServedPinIsRecordedLikeAnyOutputSoDownstreamNodeReadsWork() {
        pin("n2", "{\"name\":\"Ada\"}");

        run(node("n2", "code", Map.of("operation", "uppercase")), "ignored", draft());

        WorkflowRunNodeOutput row =
                recorded.stream().filter(o -> o.getNodeId().equals("n2")).findFirst().orElseThrow();
        assertEquals("PINNED", row.getStatus());
        assertEquals("{\"name\":\"Ada\"}", row.getOutputText());
        // A downstream node reads it through $node["N2"] exactly as it would a real output.
        WorkflowNodeExecutor.NodeExecutionResult next =
                run(
                        node(
                                "n3",
                                "code",
                                Map.of(
                                        "operation",
                                        "template",
                                        "template",
                                        "Hi {{ $node[\"N2\"].name }}")),
                        "{}",
                        draft());
        assertEquals("OK", next.status());
        assertEquals("Hi Ada", next.output());
    }

    @Test
    void aPinnedTriggerBecomesTheRunsTriggerPayload() {
        pin("t1", "{\"event\":\"order.created\"}");

        WorkflowNodeExecutor.NodeExecutionResult r =
                run(node("t1", "trigger", Map.of()), "live webhook body", draft());

        assertEquals("PINNED", r.status());
        assertEquals("{\"event\":\"order.created\"}", r.output());
        WorkflowRunNodeOutput trigger =
                recorded.stream()
                        .filter(o -> o.getNodeId().equals(RunDataStore.TRIGGER_NODE_ID))
                        .findFirst()
                        .orElseThrow();
        assertEquals("{\"event\":\"order.created\"}", trigger.getOutputText());
        WorkflowNodeExecutor.NodeExecutionResult next =
                run(
                        node(
                                "n3",
                                "code",
                                Map.of(
                                        "operation",
                                        "template",
                                        "template",
                                        "{{ $trigger.event }}")),
                        "x",
                        draft());
        assertEquals("order.created", next.output());
    }

    @Test
    void anExecutorWithoutAPinStoreNeverServesPins() {
        executor.setPinStore(null);
        pin("n2", "pinned");
        assertFalse(
                "PINNED"
                        .equals(
                                run(
                                                node(
                                                        "n2",
                                                        "code",
                                                        Map.of("operation", "uppercase")),
                                                "hello",
                                                draft())
                                        .status()));
    }

    @Test
    void thePinStateOfTheScopeIsWhatDecides() {
        assertFalse(
                ExpressionScope.forMode(null, RunMode.PRODUCTION) != null,
                "production binds no scope at all");
        assertTrue(draft().honoursPins());
        assertEquals("draft", draft().mode());
        assertTrue(
                ExpressionScope.forMode(
                                ExpressionScope.iteration("a", 2, List.of("a")), RunMode.DRAFT)
                        .honoursPins());
        assertEquals(
                2,
                ExpressionScope.forMode(
                                ExpressionScope.iteration("a", 2, List.of("a")), RunMode.DRAFT)
                        .index());
    }
}
