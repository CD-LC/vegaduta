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
import com.agenticai.core.agents.turn.ChatTurnRequest;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * Dynamic Fan-Out ("fan_out") on the default synchronous engine - the runtime-sized AND concurrent
 * construct {@code LOOP} (runtime-sized, sequential) and {@code PARALLEL} (concurrent,
 * design-time-sized) between them left open; see {@link WorkflowNodeType}'s {@code FAN_OUT} javadoc
 * for the full design. Exercised end-to-end through the real {@link WorkflowNodeExecutor} (not a
 * mock of it), same harness shape as {@link WorkflowExecutionEngineFailProofTest} - the assertions
 * here pin down the behaviors that make this node type what it is: one worker execution per RUNTIME
 * item, item-order (not completion-order) output merging, the maxItems cap, and the pre-flight
 * clamp-to-step-budget semantics that deliberately deviate from Loop's fail-at-item-N behavior.
 */
class WorkflowExecutionEngineFanOutTest {

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
        return new ObjectMapper().registerModule(new JavaTimeModule());
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

    /** trigger -> fan_out -> worker(agent) -> collector(agent), input a 3-item JSON array. */
    private static WorkflowGraphDto fanOutGraph(
            Map<String, Object> fanOutConfig, boolean withCollector) {
        WorkflowNodeDto trigger = node("trigger1", "trigger", Map.of());
        WorkflowNodeDto fanOut = node("fanout1", "fan_out", fanOutConfig);
        WorkflowNodeDto worker = node("worker1", "agent", Map.of("agentId", AGENT_ID.toString()));
        if (!withCollector) {
            return new WorkflowGraphDto(
                    List.of(trigger, fanOut, worker),
                    List.of(
                            new WorkflowEdgeDto("e1", "trigger1", "fanout1", null, null),
                            new WorkflowEdgeDto("e2", "fanout1", "worker1", null, null)));
        }
        WorkflowNodeDto collector =
                node("collector1", "agent", Map.of("agentId", AGENT_ID.toString()));
        return new WorkflowGraphDto(
                List.of(trigger, fanOut, worker, collector),
                List.of(
                        new WorkflowEdgeDto("e1", "trigger1", "fanout1", null, null),
                        new WorkflowEdgeDto("e2", "fanout1", "worker1", null, null),
                        new WorkflowEdgeDto("e3", "worker1", "collector1", null, null)));
    }

    /**
     * The core contract: a 3-item runtime array fans out to exactly 3 worker executions (the graph
     * only draws the worker once - the width is DATA), and the node past the fan-in receives the
     * ITEM-ORDER JSON-array merge of the worker's outputs - item order, not completion order, since
     * with real concurrency in play completion order is nondeterministic and a downstream node's
     * input must not be.
     */
    @Test
    void runsWorkerOncePerRuntimeItemAndMergesOutputsInItemOrder()
            throws ReflectiveOperationException {
        WorkflowGraphDto graph = fanOutGraph(Map.of("maxConcurrency", 2), true);

        // Worker calls echo their per-item input; the collector call (the one
        // whose input is the merged JSON array) returns a terminal reply.
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.turnRef() != null)))
                .thenAnswer(
                        invocation -> {
                            String input = invocation.<ChatTurnRequest>getArgument(1).userMessage();
                            return input.startsWith("[")
                                    ? Flux.just("done")
                                    : Flux.just("scored-" + input);
                        });

        WorkflowRun run =
                engine.execute(definitionWithId(), graph, "[\"a\",\"b\",\"c\"]", "system:test");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        ArgumentCaptor<ChatTurnRequest> turns = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(agentChatService, times(4)).chat(any(TurnContext.class), turns.capture());
        assertTrue(
                turns.getAllValues().stream()
                        .allMatch(r -> AGENT_ID.equals(r.agentId()) && r.turnRef() != null));
        List<String> inputs =
                turns.getAllValues().stream().map(ChatTurnRequest::userMessage).toList();
        // 3 worker calls, one per item (order of INVOCATION is deliberately
        // not asserted - they run concurrently), plus the collector call fed
        // the item-order merge.
        assertTrue(
                inputs.containsAll(List.of("a", "b", "c")),
                "expected one worker call per item, got: " + inputs);
        assertTrue(
                inputs.contains("[\"scored-a\",\"scored-b\",\"scored-c\"]"),
                "expected the collector to receive the item-order merged array, got: " + inputs);
        // Per-item trace entries plus the fan-out node's own summary entry.
        assertTrue(
                run.getTraceJson().contains("(fan-out 1/3)"),
                "expected per-item fan-out trace labels, got: " + run.getTraceJson());
        assertTrue(
                run.getTraceJson()
                        .contains("Fanned out 'worker1' across 3 of 3 item(s), 2 at a time"),
                "expected the fan-out summary entry, got: " + run.getTraceJson());
    }

    /** maxItems clamps a longer runtime list, and the summary names WHICH cap did it. */
    @Test
    void maxItemsCapsTheWorkedItemSetAndTheSummarySaysSo() throws ReflectiveOperationException {
        WorkflowGraphDto graph = fanOutGraph(Map.of("maxItems", 2), false);

        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.turnRef() != null)))
                .thenAnswer(
                        invocation ->
                                Flux.just(
                                        "scored-"
                                                + invocation
                                                        .<ChatTurnRequest>getArgument(1)
                                                        .userMessage()));

        WorkflowRun run =
                engine.execute(
                        definitionWithId(), graph, "[\"a\",\"b\",\"c\",\"d\"]", "system:test");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        verify(agentChatService, times(2))
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.turnRef() != null));
        assertTrue(
                run.getTraceJson().contains("capped at 2 by this node's maxItems"),
                "expected the maxItems cap to be named in the summary, got: " + run.getTraceJson());
    }

    /**
     * Pre-flight step-budget clamping - the deliberate deviation from Loop's post-hoc charging
     * (which would run items until the budget ran out and then FAIL the whole run mid-list): with
     * maxStepsPerRun=4, the trigger and fan-out node consume 2 steps, so only 2 of the 5 items are
     * worked, the truncation is named in the trace, and the run still COMPLETES.
     */
    @Test
    void fanOutClampsToTheRemainingStepBudgetInsteadOfFailingMidList()
            throws ReflectiveOperationException {
        WorkflowGraphDto graph = fanOutGraph(Map.of(), false);
        WorkflowDefinition definition = definitionWithId();
        definition.setExecutionLimits(4, 30);

        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.turnRef() != null)))
                .thenAnswer(
                        invocation ->
                                Flux.just(
                                        "scored-"
                                                + invocation
                                                        .<ChatTurnRequest>getArgument(1)
                                                        .userMessage()));

        WorkflowRun run =
                engine.execute(definition, graph, "[\"a\",\"b\",\"c\",\"d\",\"e\"]", "system:test");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        verify(agentChatService, times(2))
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.turnRef() != null));
        assertTrue(
                run.getTraceJson().contains("truncated to the run's remaining 2-step budget"),
                "expected the step-budget truncation to be named in the summary, got: "
                        + run.getTraceJson());
    }

    /**
     * Loop-parity for the degenerate input: anything that doesn't resolve to a JSON array fans out
     * to zero items, skips the worker entirely, and the run continues past it with "[]" - a
     * misconfigured upstream node is visible in the fan-out's own trace summary, never a run-ending
     * error (same documented posture as resolveLoopItems).
     */
    @Test
    void nonArrayInputFansOutToZeroItemsAndContinues() throws ReflectiveOperationException {
        WorkflowGraphDto graph = fanOutGraph(Map.of(), true);

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

        WorkflowRun run =
                engine.execute(
                        definitionWithId(), graph, "plain text, not an array", "system:test");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        // Only the collector ran (fed the empty merge) - never the worker.
        ArgumentCaptor<ChatTurnRequest> turns = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(agentChatService, times(1)).chat(any(TurnContext.class), turns.capture());
        assertTrue(
                turns.getAllValues().stream()
                        .allMatch(r -> AGENT_ID.equals(r.agentId()) && r.turnRef() != null));
        List<String> inputs =
                turns.getAllValues().stream().map(ChatTurnRequest::userMessage).toList();
        assertEquals(List.of("[]"), inputs);
        assertTrue(
                run.getTraceJson().contains("across 0 of 0 item(s)"),
                "expected the zero-item fan-out to be visible in the summary, got: "
                        + run.getTraceJson());
    }
}
