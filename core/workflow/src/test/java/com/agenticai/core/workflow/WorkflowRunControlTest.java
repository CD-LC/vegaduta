package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.agenticai.core.workflow.temporal.TemporalWorkflowExecutionEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pause/resume + paused-cancel (V261) on the service seam - all collaborators
 * mocked. Pins the operator-facing contract:
 * <ul>
 *   <li>{@code pauseRun} only signals a RUNNING run (anything else is a
 *       no-op returning the current row - same contract as Stop);</li>
 *   <li>{@code cancelRun} on a PAUSED run is a guarded CAS to CANCELLED -
 *       no engine signal (nothing is in flight), envelope cleared;</li>
 *   <li>{@code resumeRun} on PAUSED claims PAUSED&rarr;RUNNING via CAS,
 *       clears the envelope, and re-enters the engine at the parked node
 *       with its recorded input; any other state is a no-op.</li>
 * </ul>
 */
class WorkflowRunControlTest {

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();

    private WorkflowRunRepository runRepository;
    private WorkflowDefinitionRepository definitionRepository;
    private WorkflowRunCancellationService cancellationService;
    private WorkflowRunPauseService pauseService;
    private WorkflowExecutionEngine executionEngine;
    private WorkflowService service;
    private WorkflowBackgroundRunService backgroundRunService;

    @BeforeEach
    void setUp() {
        runRepository = mock(WorkflowRunRepository.class);
        definitionRepository = mock(WorkflowDefinitionRepository.class);
        cancellationService = mock(WorkflowRunCancellationService.class);
        pauseService = mock(WorkflowRunPauseService.class);
        executionEngine = mock(WorkflowExecutionEngine.class);
        ObjectProvider<TemporalWorkflowExecutionEngine> temporal = mock(ObjectProvider.class);
        when(temporal.getIfAvailable()).thenReturn(null);

        com.agenticai.core.workflow.version.WorkflowVersionService versionService =
            mock(com.agenticai.core.workflow.version.WorkflowVersionService.class);
        // C1: unpinned lookups fall back to the definition's own graph -
        // the mock returns null otherwise, which readGraph rejects.
        when(versionService.resolveRunGraphJson(any(WorkflowDefinition.class)))
            .thenAnswer(inv -> inv.getArgument(0, WorkflowDefinition.class).getGraphJson());
        when(versionService.getOptional(any(), any(int.class)))
            .thenReturn(Optional.empty());

        service = WorkflowServiceFixture.of(
            definitionRepository, runRepository, mock(OrchestratorService.class),
            executionEngine, new ObjectMapper(), mock(TenantResolutionService.class),
            mock(TenantSchemaResolver.class), mock(TenantLimits.class), mock(WorkflowTriggerNodeService.class),
            temporal, mock(EncryptionService.class), cancellationService,
            pauseService, mock(ResourceAccessService.class), mock(WorkflowExecutionParamsInferenceService.class),
            versionService
        );

        // The background service wraps the SAME service instance so its
        // getRunEntity/readGraph calls exercise the real (mocked-repo) path.
        backgroundRunService = new WorkflowBackgroundRunService(
            service, executionEngine, runRepository, new ObjectMapper());
    }

    private WorkflowRun run(WorkflowRunStatus status) {
        WorkflowRun r = new WorkflowRun(RUN_ID, WORKFLOW_ID, "Lead router", "user@example.com");
        if (status == WorkflowRunStatus.PAUSED) {
            r.pause("[]", "node-7", "previous node output");
        } else if (status != WorkflowRunStatus.RUNNING) {
            r.complete(status, "[]", null, null, null);
        }
        when(runRepository.findByIdAndWorkflowId(RUN_ID, WORKFLOW_ID)).thenReturn(Optional.of(r));
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(r));
        return r;
    }

    // ------------------------------------------------------------------
    // pauseRun
    // ------------------------------------------------------------------

    @Test
    void pauseSignalsOnlyRunningRuns() {
        run(WorkflowRunStatus.RUNNING);

        WorkflowRunResponse response = service.pauseRun(WORKFLOW_ID, RUN_ID);

        verify(pauseService).requestPause(RUN_ID);
        assertEquals(WorkflowRunStatus.RUNNING, response.status());
    }

    @Test
    void pauseOnFinishedRunIs409WithTheCurrentStatus() {
        run(WorkflowRunStatus.COMPLETED);

        ResponseStatusException e =
            assertThrows(ResponseStatusException.class, () -> service.pauseRun(WORKFLOW_ID, RUN_ID));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertTrue(e.getReason().contains("COMPLETED"), e.getReason());
        verify(pauseService, never()).requestPause(any());
    }

    @Test
    void cancelOnFinishedRunIs409WithTheCurrentStatus() {
        for (WorkflowRunStatus terminal : List.of(
                WorkflowRunStatus.COMPLETED, WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED)) {
            run(terminal);

            ResponseStatusException e =
                assertThrows(ResponseStatusException.class, () -> service.cancelRun(WORKFLOW_ID, RUN_ID));

            assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
            assertTrue(e.getReason().contains(terminal.name()), e.getReason());
        }
        verify(cancellationService, never()).requestCancellation(any());
    }

    @Test
    void cancelOnRunningRunSignalsAndSaysSoInTheBody() {
        run(WorkflowRunStatus.RUNNING);

        WorkflowRunResponse response = service.cancelRun(WORKFLOW_ID, RUN_ID);

        verify(cancellationService).requestCancellation(RUN_ID);
        // Still RUNNING (observed between nodes) but no longer indistinguishable from a no-op.
        assertEquals(WorkflowRunStatus.RUNNING, response.status());
        assertTrue(response.cancelRequested());
    }

    // ------------------------------------------------------------------
    // cancelRun on PAUSED
    // ------------------------------------------------------------------

    @Test
    void cancelOnPausedRunIsAPlainStatusTransition() {
        WorkflowRun run = run(WorkflowRunStatus.PAUSED);
        when(runRepository.compareAndSetStatus(RUN_ID, WorkflowRunStatus.PAUSED, WorkflowRunStatus.CANCELLED))
            .thenReturn(1);

        WorkflowRunResponse response = service.cancelRun(WORKFLOW_ID, RUN_ID);

        // No engine signal - nothing is executing to interrupt.
        verify(cancellationService, never()).requestCancellation(any());
        verify(runRepository).save(run);
        assertEquals(WorkflowRunStatus.CANCELLED, response.status());
        assertNull(run.getPausedNodeId());
        verify(pauseService).clearPauseFlag(RUN_ID);
    }

    @Test
    void cancelOnPausedRunThatLostTheCasRaceIsANoOp() {
        run(WorkflowRunStatus.PAUSED);
        when(runRepository.compareAndSetStatus(RUN_ID, WorkflowRunStatus.PAUSED, WorkflowRunStatus.CANCELLED))
            .thenReturn(0); // a resume got there first

        service.cancelRun(WORKFLOW_ID, RUN_ID);

        verify(runRepository, never()).save(any(WorkflowRun.class));
    }

    // ------------------------------------------------------------------
    // resumeRun
    // ------------------------------------------------------------------

    @Test
    void resumeOnFinishedRunIs409AndOnARunningRunIsANoOp() {
        run(WorkflowRunStatus.COMPLETED);
        assertThrows(ResponseStatusException.class, () -> backgroundRunService.resumeRun(WORKFLOW_ID, RUN_ID));

        run(WorkflowRunStatus.RUNNING);
        backgroundRunService.resumeRun(WORKFLOW_ID, RUN_ID);

        verify(executionEngine, never()).resume(any(), any(), any(), any(), any(), any(), any());
        verify(runRepository, never()).compareAndSetStatus(any(), any(), any());
    }

    @Test
    void resumeClaimsTheRunAndReEntersAtTheParkedNode() {
        WorkflowRun run = run(WorkflowRunStatus.PAUSED);
        when(runRepository.compareAndSetStatus(RUN_ID, WorkflowRunStatus.PAUSED, WorkflowRunStatus.RUNNING))
            .thenReturn(1);
        WorkflowDefinition definition = mock(WorkflowDefinition.class);
        when(definition.getGraphJson()).thenReturn("{\"nodes\":[],\"edges\":[]}");
        when(definitionRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(definition));

        WorkflowRunResponse response = backgroundRunService.resumeRun(WORKFLOW_ID, RUN_ID);

        // The CAS claimed the run, the envelope was read and cleared, and the
        // engine was asked to re-enter at the parked node with its recorded
        // input - asynchronously on the pool, hence the timeout verify.
        verify(runRepository).save(run);
        assertNull(run.getPausedNodeId());
        assertNull(run.getPausedNodeInput());
        verify(executionEngine, timeout(2000)).resume(
            eq(definition), any(), eq(run), any(List.class),
            eq("node-7"), eq("previous node output"), eq("user@example.com"));
        assertEquals(WorkflowRunStatus.RUNNING, response.status());
    }

    @Test
    void resumeWithoutAnEnvelopeFailsTheRunHonestly() {
        // A PAUSED row with no paused_node_id (pre-V261 row or a
        // half-persisted pause) can't re-enter the graph - it must not
        // strand PAUSED forever.
        WorkflowRun run = run(WorkflowRunStatus.PAUSED);
        run.clearPauseEnvelope();
        when(runRepository.compareAndSetStatus(RUN_ID, WorkflowRunStatus.PAUSED, WorkflowRunStatus.RUNNING))
            .thenReturn(1);

        WorkflowRunResponse response = backgroundRunService.resumeRun(WORKFLOW_ID, RUN_ID);

        verify(runRepository).save(run);
        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertEquals(WorkflowRunStatus.FAILED, response.status());
        verify(executionEngine, never()).resume(any(), any(), any(), any(), any(), any(), any());
    }
}
