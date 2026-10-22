package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The background start path is what the canvas Run button really uses (background: true, mode
 * "draft"), so which graph it executes and which mode it stamps on the row are pinned here against
 * the real service with a mocked workflow service and engine. Mutating either choice used to leave
 * every other test green.
 */
class WorkflowBackgroundRunServiceModeTest {

    private static final UUID WF = UUID.randomUUID();

    private WorkflowService workflowService;
    private WorkflowExecutionEngine engine;
    private WorkflowBackgroundRunService service;
    private WorkflowDefinition definition;
    private final WorkflowGraphDto live = new WorkflowGraphDto(List.of(), List.of());
    private final WorkflowGraphDto snapshot = new WorkflowGraphDto(List.of(), List.of());

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        engine = mock(WorkflowExecutionEngine.class);
        definition = mock(WorkflowDefinition.class);
        when(definition.getId()).thenReturn(WF);
        when(definition.getName()).thenReturn("wf");
        when(definition.getGraphJson()).thenReturn("{\"live\":true}");
        when(workflowService.get(WF)).thenReturn(definition);
        when(workflowService.readGraph("{\"live\":true}")).thenReturn(live);
        when(workflowService.resolveRunGraph(definition)).thenReturn(snapshot);
        service =
                new WorkflowBackgroundRunService(
                        workflowService,
                        engine,
                        mock(WorkflowRunRepository.class),
                        new ObjectMapper());
    }

    /**
     * Stubs prepareRun for either overload and captures the graph continueRun receives on the
     * worker thread.
     */
    private AtomicReference<WorkflowGraphDto> captureContinueGraph(CountDownLatch done) {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), WF, "wf", "ann");
        when(engine.prepareRun(any(), any(), any(), any())).thenReturn(run);
        when(engine.prepareRun(any(), any(), any(), any(), any())).thenReturn(run);
        AtomicReference<WorkflowGraphDto> seen = new AtomicReference<>();
        when(engine.continueRun(any(), any(), any(), any(), any(), any(), anyList()))
                .thenAnswer(
                        inv -> {
                            seen.set(inv.getArgument(1));
                            done.countDown();
                            return run;
                        });
        return seen;
    }

    @Test
    void aDraftRunExecutesTheLiveGraphAndStampsDraftOnTheRow() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<WorkflowGraphDto> continued = captureContinueGraph(done);

        service.start(WF, "in", "ann", null, List.of(), RunMode.DRAFT);

        assertTrue(done.await(5, TimeUnit.SECONDS), "the traversal must be handed to the worker");
        verify(workflowService).readGraph("{\"live\":true}");
        verify(workflowService, never()).resolveRunGraph(any());
        verify(engine)
                .prepareRun(eq(definition), same(live), isNull(), eq("ann"), eq(RunMode.DRAFT));
        verify(engine, never()).prepareRun(any(), any(), any(), any());
        assertSame(
                live,
                continued.get(),
                "the worker runs the graph being edited, not the promoted snapshot");
    }

    @Test
    void aProductionRunExecutesThePromotedSnapshotAndNeverTheLiveGraph() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<WorkflowGraphDto> continued = captureContinueGraph(done);

        service.start(WF, "in", "ann", null, List.of(), RunMode.PRODUCTION);

        assertTrue(done.await(5, TimeUnit.SECONDS));
        verify(workflowService).resolveRunGraph(definition);
        verify(workflowService, never()).readGraph(any());
        verify(engine).prepareRun(eq(definition), same(snapshot), isNull(), eq("ann"));
        verify(engine, never()).prepareRun(any(), any(), any(), any(), any());
        assertSame(snapshot, continued.get());
    }

    @Test
    void theShortOverloadAndANullModeAreProduction() throws Exception {
        CountDownLatch done = new CountDownLatch(2);
        captureContinueGraph(done);

        service.start(WF, "in", "ann", null, List.of());
        service.start(WF, "in", "ann", null, List.of(), null);

        assertTrue(done.await(5, TimeUnit.SECONDS));
        verify(workflowService, never()).readGraph(any());
        assertEquals(
                2,
                org.mockito.Mockito.mockingDetails(workflowService).getInvocations().stream()
                        .filter(i -> i.getMethod().getName().equals("resolveRunGraph"))
                        .count());
    }

    private static <T> T same(T value) {
        return org.mockito.ArgumentMatchers.same(value);
    }
}
