package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import com.agenticai.core.workflow.temporal.TemporalWorkflowExecutionEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Security audit 2026-07-29, finding F3 - regression cover for the per-run IDOR on the three {@code
 * /api/workflows/{workflowId}/runs/{runId}...} endpoints.
 *
 * <p>The bug: {@code WorkflowController} access-checks the PATH's {@code workflowId} via {@link
 * WorkflowService#requireAccess}, but the run itself used to be loaded by {@code runId} alone
 * ({@code runRepository.findById}). The two ids were never tied together, so an attacker could pass
 * a workflow they legitimately own plus a victim's {@code runId}: the check passed against their
 * own workflow and the response carried the victim's run - {@code WorkflowRunResponse.trace} (the
 * per-node inputs and outputs, i.e. the data the other user's workflow actually processed) for
 * {@code getRun}, the output file for {@code /download}, and a live cancellation for {@code
 * /cancel}.
 *
 * <p>These tests drive {@link WorkflowService} directly rather than through the controller (this
 * codebase has no MockMvc/web-layer test harness anywhere - see {@code AccountControllerTest}'s own
 * note - and the controller methods are thin pass-throughs). The repository mock below does REAL
 * filtering rather than per-id stubbing, so a service method that passed the wrong {@code
 * workflowId} through would fail these tests rather than quietly matching a stub.
 */
class WorkflowRunScopingSecurityTest {

    private static final UUID ATTACKER_WORKFLOW = UUID.randomUUID();
    private static final UUID VICTIM_WORKFLOW = UUID.randomUUID();

    private WorkflowRunRepository runRepository;
    private WorkflowRunCancellationService cancellationService;
    private WorkflowService service;

    /**
     * The victim's run - the row an attacker must never reach through their own workflow's path.
     */
    private WorkflowRun victimRun;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runRepository = mock(WorkflowRunRepository.class);
        cancellationService = mock(WorkflowRunCancellationService.class);
        ObjectProvider<TemporalWorkflowExecutionEngine> temporalExecutionEngine =
                mock(ObjectProvider.class);

        service =
                WorkflowServiceFixture.of(
                        mock(WorkflowDefinitionRepository.class),
                        runRepository,
                        mock(OrchestratorService.class),
                        mock(WorkflowExecutionEngine.class),
                        new ObjectMapper(),
                        mock(TenantResolutionService.class),
                        mock(TenantSchemaResolver.class),
                        mock(TenantLimits.class),
                        mock(WorkflowTriggerNodeService.class),
                        temporalExecutionEngine,
                        mock(EncryptionService.class),
                        cancellationService,
                        mock(WorkflowRunPauseService.class),
                        mock(ResourceAccessService.class),
                        mock(WorkflowExecutionParamsInferenceService.class),
                        mock(com.agenticai.core.workflow.version.WorkflowVersionService.class));

        victimRun =
                new WorkflowRun(
                        UUID.randomUUID(), VICTIM_WORKFLOW, "Victim payroll export", "victim-sub");
        victimRun.complete(
                WorkflowRunStatus.COMPLETED,
                "[{\"node\":\"agent\",\"output\":\"secret salary data\"}]",
                null,
                "payroll.txt",
                "secret salary data");

        // Real filtering, not per-argument stubbing: the binding this test is
        // about is exactly "does the query use BOTH ids", so the fake enforces it.
        List<WorkflowRun> rows = List.of(victimRun);
        when(runRepository.findByIdAndWorkflowId(any(UUID.class), any(UUID.class)))
                .thenAnswer(
                        invocation -> {
                            UUID runId = invocation.getArgument(0);
                            UUID workflowId = invocation.getArgument(1);
                            return rows.stream()
                                    .filter(
                                            r ->
                                                    r.getId().equals(runId)
                                                            && r.getWorkflowId().equals(workflowId))
                                    .findFirst();
                        });
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // -- A victim's run is unreachable through the attacker's own workflow ---

    @Test
    void getRunDoesNotReturnAnotherWorkflowsRun() {
        // The attacker passes their OWN workflow id (which requireAccess would
        // happily approve) plus the victim's run id.
        WorkflowRunNotFoundException thrown =
                assertThrows(
                        WorkflowRunNotFoundException.class,
                        () -> service.getRun(ATTACKER_WORKFLOW, victimRun.getId()));
        // 404, not 403 - the caller learns nothing about whether that run id is
        // real, matching this codebase's anti-enumeration convention.
        assertEquals("Workflow run " + victimRun.getId() + " not found", thrown.getMessage());
        // And the unscoped finder is never reached at all.
        verify(runRepository, never()).findById(any(UUID.class));
    }

    @Test
    void downloadDoesNotReturnAnotherWorkflowsRunOutputFile() {
        assertThrows(
                WorkflowRunNotFoundException.class,
                () -> service.getRunEntity(ATTACKER_WORKFLOW, victimRun.getId()));
        verify(runRepository, never()).findById(any(UUID.class));
    }

    @Test
    void cancelDoesNotStopAnotherWorkflowsRun() {
        WorkflowRun running =
                new WorkflowRun(
                        UUID.randomUUID(), VICTIM_WORKFLOW, "Victim long job", "victim-sub");
        when(runRepository.findByIdAndWorkflowId(running.getId(), VICTIM_WORKFLOW))
                .thenReturn(Optional.of(running));

        assertThrows(
                WorkflowRunNotFoundException.class,
                () -> service.cancelRun(ATTACKER_WORKFLOW, running.getId()));
        // Critically: no cancellation signal was emitted for the victim's run.
        verify(cancellationService, never()).requestCancellation(any(UUID.class));
        verify(runRepository, never()).findById(any(UUID.class));
    }

    // -- The legitimate path still works ------------------------------------

    @Test
    void getRunStillReturnsTheRunViaItsOwnWorkflowsPath() {
        assertEquals(victimRun.getId(), service.getRun(VICTIM_WORKFLOW, victimRun.getId()).id());
    }

    @Test
    void downloadStillReturnsTheRunViaItsOwnWorkflowsPath() {
        WorkflowRun found = service.getRunEntity(VICTIM_WORKFLOW, victimRun.getId());
        assertEquals("payroll.txt", found.getOutputFileName());
    }

    @Test
    void cancelStillSignalsARunViaItsOwnWorkflowsPath() {
        WorkflowRun running =
                new WorkflowRun(UUID.randomUUID(), VICTIM_WORKFLOW, "Long job", "victim-sub");
        when(runRepository.findByIdAndWorkflowId(running.getId(), VICTIM_WORKFLOW))
                .thenReturn(Optional.of(running));

        service.cancelRun(VICTIM_WORKFLOW, running.getId());

        verify(cancellationService).requestCancellation(running.getId());
    }

    @Test
    void cancelOnAnAlreadyFinishedRunIsA409NamingItsStatus() {
        // SE-54: victimRun is COMPLETED; a stop that stopped nothing is no longer a 200.
        org.springframework.web.server.ResponseStatusException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> service.cancelRun(VICTIM_WORKFLOW, victimRun.getId()));
        assertEquals(409, e.getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertTrue(e.getReason().contains("COMPLETED"));
        verify(cancellationService, never()).requestCancellation(any(UUID.class));
    }
}
