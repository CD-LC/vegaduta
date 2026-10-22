package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The real {@link WorkflowExecutionEngine} driving the real {@link WorkflowNodeExecutor} over pins:
 * this is the proof that (1) a production run executes a pinned node, reaching the pin store not at
 * all, and (2) a draft run emits PINNED for it and carries on downstream. Only the collaborators
 * around them (repositories, event bus, LLM/tool services) are mocks; the nodes used are pure
 * Transform ("code") nodes so nothing else is needed.
 */
class WorkflowExecutionEngineDraftRunTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private WorkflowPinStore pinStore;
    private WorkflowExecutionEngine engine;
    private WorkflowDefinition definition;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        pinStore = spy(new WorkflowPinStore(PinTestSupport.pinRepository()));
        WorkflowNodeExecutor executor =
                WorkflowNodeExecutorPinTest.newExecutor(mock(OrchestratorService.class), mapper);
        executor.setPinStore(pinStore);
        engine =
                new WorkflowExecutionEngine(
                        mock(WorkflowRunRepository.class),
                        mock(OrchestratorService.class),
                        executor,
                        mock(WorkflowEventPublisher.class),
                        mapper,
                        mock(PlatformIssueService.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(WorkflowRunCancellationService.class),
                        mock(WorkflowRunPauseService.class),
                        null,
                        null,
                        new com.agenticai.core.security.concurrent.ContextPropagatingExecutor(
                                "test-branch-", 16));
        definition =
                new WorkflowDefinition(
                        "WF", "d", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.ACTIVE);
        java.lang.reflect.Field id = WorkflowDefinition.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(definition, UUID.randomUUID());
    }

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("config", config, "label", id));
    }

    private static WorkflowNodeDto upper(String id) {
        return node(id, "code", Map.of("operation", "uppercase"));
    }

    private static WorkflowEdgeDto edge(String from, String to) {
        return new WorkflowEdgeDto(from + "-" + to, from, to, null, null);
    }

    /** trigger t1 -> n2 -> n3, every node but the trigger an uppercase transform. */
    private static WorkflowGraphDto chain() {
        return new WorkflowGraphDto(
                List.of(node("t1", "trigger", Map.of()), upper("n2"), upper("n3")),
                List.of(edge("t1", "n2"), edge("n2", "n3")));
    }

    private void pin(String nodeId, String data) {
        pinStore.save(definition.getId(), nodeId, data, "manual", null, "ann", null);
        clearInvocations(pinStore);
    }

    private List<NodeTraceEntry> trace(WorkflowRun run) throws Exception {
        return mapper.readValue(run.getTraceJson(), new TypeReference<List<NodeTraceEntry>>() {});
    }

    private static NodeTraceEntry entry(List<NodeTraceEntry> trace, String nodeId) {
        return trace.stream().filter(e -> e.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    @Test
    void aProductionRunExecutesAPinnedNodeForReal() throws Exception {
        pin("n2", "pinned value");

        // The exact call every trigger path ends in: no mode argument.
        WorkflowRun run =
                engine.execute(definition, chain(), null, "hello", "webhook", null, List.of());

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        assertEquals(RunMode.PRODUCTION, run.getRunMode());
        List<NodeTraceEntry> trace = trace(run);
        assertEquals(
                List.of("OK", "OK", "OK"), trace.stream().map(NodeTraceEntry::status).toList());
        assertEquals(
                "HELLO", entry(trace, "n2").outputJson(), "the node ran; the pin was not used");
        assertEquals("HELLO", entry(trace, "n3").outputJson());
        verify(pinStore, never()).find(any(), anyString());
        verify(pinStore, never()).list(any());
        assertEquals(0, WorkflowRunResponse.from(run, mapper).pinnedNodeCount());
        assertEquals("production", WorkflowRunResponse.from(run, mapper).mode());
    }

    @Test
    void aProductionRunThroughTheExplicitModeOverloadIsTheSame() throws Exception {
        pin("n2", "pinned value");
        WorkflowRun run =
                engine.execute(
                        definition,
                        chain(),
                        null,
                        "hello",
                        "u",
                        null,
                        List.of(),
                        RunMode.PRODUCTION);
        assertEquals("HELLO", entry(trace(run), "n2").outputJson());
        verify(pinStore, never()).find(any(), anyString());
    }

    @Test
    void aDraftRunEmitsPinnedForTheNodeAndContinuesDownstream() throws Exception {
        pin("n2", "pinned value");

        WorkflowRun run =
                engine.execute(
                        definition, chain(), null, "hello", "ann", null, List.of(), RunMode.DRAFT);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        assertEquals(RunMode.DRAFT, run.getRunMode());
        List<NodeTraceEntry> trace = trace(run);
        assertEquals(
                List.of("OK", "PINNED", "OK"), trace.stream().map(NodeTraceEntry::status).toList());
        assertEquals("pinned value", entry(trace, "n2").outputJson());
        assertEquals(
                "PINNED VALUE",
                entry(trace, "n3").outputJson(),
                "downstream received the pin as its input");
        WorkflowRunResponse response = WorkflowRunResponse.from(run, mapper);
        assertEquals(1, response.pinnedNodeCount());
        assertEquals("draft", response.mode());
    }

    @Test
    void aDraftRunWithNoPinsRunsEverythingLikeProduction() throws Exception {
        WorkflowRun run =
                engine.execute(
                        definition, chain(), null, "hello", "ann", null, List.of(), RunMode.DRAFT);
        assertEquals(
                List.of("OK", "OK", "OK"),
                trace(run).stream().map(NodeTraceEntry::status).toList());
        assertEquals(0, WorkflowRunResponse.from(run, mapper).pinnedNodeCount());
    }

    @Test
    void theModeComesFromTheRunRowSoAResumedOrBackgroundRunKeepsIt() throws Exception {
        pin("n2", "pinned value");
        WorkflowRun row =
                new WorkflowRun(UUID.randomUUID(), definition.getId(), definition.getName(), "ann");
        row.setRunMode(RunMode.DRAFT);

        WorkflowRun run =
                engine.continueRun(definition, chain(), row, null, "hello", "ann", List.of());

        assertEquals("PINNED", entry(trace(run), "n2").status());
    }

    @Test
    void aPinnedLoopBodyIsServedForEveryItemInDraftAndExecutedInProduction() throws Exception {
        WorkflowGraphDto loop =
                new WorkflowGraphDto(
                        List.of(
                                node("t1", "trigger", Map.of()),
                                node("lp", "loop", Map.of()),
                                upper("body")),
                        List.of(edge("t1", "lp"), edge("lp", "body")));
        pin("body", "P");

        WorkflowRun draft =
                engine.execute(
                        definition,
                        loop,
                        null,
                        "[\"a\",\"b\"]",
                        "ann",
                        null,
                        List.of(),
                        RunMode.DRAFT);
        List<NodeTraceEntry> draftBody =
                trace(draft).stream().filter(e -> e.nodeId().equals("body")).toList();
        assertEquals(
                List.of("PINNED", "PINNED"),
                draftBody.stream().map(NodeTraceEntry::status).toList());
        assertEquals("[\"P\",\"P\"]", entry(trace(draft), "lp").outputJson());

        WorkflowRun production =
                engine.execute(definition, loop, null, "[\"a\",\"b\"]", "webhook", null, List.of());
        assertEquals(
                List.of("A", "B"),
                trace(production).stream()
                        .filter(e -> e.nodeId().equals("body"))
                        .map(NodeTraceEntry::outputJson)
                        .toList());
    }

    @Test
    void aPinnedParallelBranchIsServedInDraftOnly() throws Exception {
        WorkflowGraphDto parallel =
                new WorkflowGraphDto(
                        List.of(
                                node("t1", "trigger", Map.of()),
                                node("par", "parallel", Map.of()),
                                upper("b1"),
                                upper("b2")),
                        List.of(edge("t1", "par"), edge("par", "b1"), edge("par", "b2")));
        pin("b1", "pinned branch");

        WorkflowRun draft =
                engine.execute(
                        definition, parallel, null, "x", "ann", null, List.of(), RunMode.DRAFT);
        assertEquals("PINNED", entry(trace(draft), "b1").status());
        assertEquals("OK", entry(trace(draft), "b2").status());

        WorkflowRun production =
                engine.execute(definition, parallel, null, "x", "webhook", null, List.of());
        assertEquals("OK", entry(trace(production), "b1").status());
        assertEquals("X", entry(trace(production), "b1").outputJson());
    }

    @Test
    void pinnedIsSuccessLikeSoALaterNodeStillRunsAndOnlyPinnedEntriesAreCounted() throws Exception {
        pin("n2", "pinned");
        WorkflowRun run =
                engine.execute(
                        definition, chain(), null, "x", "ann", null, List.of(), RunMode.DRAFT);
        List<NodeTraceEntry> trace = trace(run);
        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        assertEquals(1, WorkflowExecutionEngine.pinnedCount(trace));
        assertEquals("OK", entry(trace, "n3").status());
    }
}
