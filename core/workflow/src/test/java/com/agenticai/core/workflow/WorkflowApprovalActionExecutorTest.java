package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestRepository;
import com.agenticai.core.approval.RetryableActionFailure;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.temporal.TemporalWorkflowExecutionEngine;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link WorkflowApprovalActionExecutor}'s decision contract: an approval atomically claims the
 * AWAITING_APPROVAL run and re-enters the engine at the recorded node with the recorded input under
 * the recorded tenant (restored afterwards); a lost claim race or an already-terminal run is a
 * quiet no-op (double-approve resumes once); a payload without the resume envelope declines loudly
 * WITHOUT touching the run; and a rejection marks the run FAILED with the decider and reason.
 * {@link WorkflowExecutionEngine} is mocked - the resumed traversal's own semantics live in {@code
 * WorkflowExecutionEngineResumeTest}.
 */
class WorkflowApprovalActionExecutorTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String ACTION_TYPE = "workflow.node.approval";

    private WorkflowExecutionEngine engine;
    private WorkflowRunRepository runRepository;
    private WorkflowDefinitionRepository definitionRepository;
    private ApprovalRequestRepository requestRepository;
    private WorkflowEventPublisher eventPublisher;
    private WorkflowApprovalActionExecutor executor;

    /** Same JavaTimeModule necessity as {@code WorkflowExecutionEngineFailProofTest#mapper}. */
    private static ObjectMapper mapper() {
        return com.agenticai.core.json.JsonMappers.plain();
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        engine = mock(WorkflowExecutionEngine.class);
        ObjectProvider<WorkflowExecutionEngine> engineProvider = mock(ObjectProvider.class);
        when(engineProvider.getObject()).thenReturn(engine);
        ObjectProvider<TemporalWorkflowExecutionEngine> temporalProvider =
                mock(ObjectProvider.class);
        when(temporalProvider.getIfAvailable()).thenReturn(null);
        runRepository = mock(WorkflowRunRepository.class);
        definitionRepository = mock(WorkflowDefinitionRepository.class);
        requestRepository = mock(ApprovalRequestRepository.class);
        eventPublisher = mock(WorkflowEventPublisher.class);
        executor =
                new WorkflowApprovalActionExecutor(
                        engineProvider,
                        temporalProvider,
                        runRepository,
                        definitionRepository,
                        requestRepository,
                        eventPublisher,
                        mapper());
    }

    @AfterEach
    void cleanUpThreadLocals() {
        TenantContext.clear();
        ApprovalReplayGrant.clear();
    }

    private static ApprovalRequest requestWithResumeEnvelope() {
        String payload =
                "{\"workflowName\":\"wf\",\"runId\":\""
                        + RUN_ID
                        + "\",\"nodeId\":\"a1\","
                        + "\"adminOnly\":false,\"resume\":{\"version\":1,\"nodeInput\":\"carried-input\","
                        + "\"tenantId\":\"tenant-a\",\"subject\":\"user-sub\"}}";
        return new ApprovalRequest(ACTION_TYPE, "summary", payload, "user-1");
    }

    /** The pre-resume-support payload shape - runId/nodeId only, no envelope. */
    private static ApprovalRequest legacyRequest() {
        String payload =
                "{\"workflowName\":\"wf\",\"runId\":\""
                        + RUN_ID
                        + "\",\"nodeId\":\"a1\",\"adminOnly\":false}";
        return new ApprovalRequest(ACTION_TYPE, "summary", payload, "user-1");
    }

    private static WorkflowRun runWithStatus(WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun(RUN_ID, WORKFLOW_ID, "wf", "user-1");
        if (status != WorkflowRunStatus.RUNNING) {
            run.complete(
                    status,
                    "[]",
                    status == WorkflowRunStatus.AWAITING_APPROVAL
                            ? "Awaiting human approval (request x)."
                            : null,
                    null,
                    null);
        }
        return run;
    }

    private WorkflowDefinition stubbedDefinition() throws ReflectiveOperationException {
        WorkflowDefinition definition =
                new WorkflowDefinition(
                        "wf", "desc", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.ACTIVE);
        java.lang.reflect.Field idField = WorkflowDefinition.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(definition, WORKFLOW_ID);
        when(definitionRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(definition));
        return definition;
    }

    // --- approve: happy path -----------------------------------------------

    @Test
    void approveClaimsTheRunAndResumesAtTheRecordedNodeWithTheRecordedInputUnderTheRecordedTenant()
            throws ReflectiveOperationException {
        // The deciding admin's own context - must be restored untouched.
        TenantContext.set(TenantContext.of("caller-tenant", "admin-sub", Set.of()));
        stubbedDefinition();
        WorkflowRun awaiting = runWithStatus(WorkflowRunStatus.AWAITING_APPROVAL);
        WorkflowRun claimed = runWithStatus(WorkflowRunStatus.RUNNING);
        when(runRepository.findById(RUN_ID))
                .thenReturn(Optional.of(awaiting))
                .thenReturn(Optional.of(claimed));
        when(runRepository.compareAndSetStatus(
                        RUN_ID, WorkflowRunStatus.AWAITING_APPROVAL, WorkflowRunStatus.RUNNING))
                .thenReturn(1);
        AtomicReference<TenantContext> observedDuringResume = new AtomicReference<>();
        AtomicBoolean grantHeldDuringResume = new AtomicBoolean();
        WorkflowRun finished = runWithStatus(WorkflowRunStatus.RUNNING);
        finished.complete(WorkflowRunStatus.COMPLETED, "[]", null, null, null);
        when(engine.resume(
                        any(),
                        any(),
                        eq(claimed),
                        any(),
                        eq("a1"),
                        eq("carried-input"),
                        eq("user-1")))
                .thenAnswer(
                        invocation -> {
                            observedDuringResume.set(TenantContext.current().orElse(null));
                            // consume() clears on match - exactly what the node executor's
                            // gate would do, so this also proves single-use placement.
                            grantHeldDuringResume.set(ApprovalReplayGrant.consume(ACTION_TYPE));
                            return finished;
                        });

        ApprovalRequest request = requestWithResumeEnvelope();
        executor.execute(request);

        // Resumed under the RECORDED tenant/subject, not the deciding admin's.
        assertNotNull(observedDuringResume.get());
        assertEquals("tenant-a", observedDuringResume.get().getTenantId());
        assertEquals("user-sub", observedDuringResume.get().getSubject());
        assertTrue(
                grantHeldDuringResume.get(),
                "the replay grant must be live while the engine resumes");
        // Caller's context restored afterwards; no grant lingers.
        assertEquals("caller-tenant", TenantContext.current().orElseThrow().getTenantId());
        assertFalse(ApprovalReplayGrant.consume(ACTION_TYPE));
        // Outcome persisted onto the request for the approver to see.
        assertTrue(
                request.getExecutionResult().contains("COMPLETED"),
                "execution result should carry the terminal status, got: "
                        + request.getExecutionResult());
        verify(requestRepository).save(request);
    }

    // --- approve: concurrency + no-op guards --------------------------------

    @Test
    void doubleApproveResumesOnceTheLoserOfTheClaimRaceIsAQuietNoOp() {
        WorkflowRun awaiting = runWithStatus(WorkflowRunStatus.AWAITING_APPROVAL);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(awaiting));
        // Another decision won the guarded UPDATE between our status read
        // and our CAS - zero rows updated.
        when(runRepository.compareAndSetStatus(
                        RUN_ID, WorkflowRunStatus.AWAITING_APPROVAL, WorkflowRunStatus.RUNNING))
                .thenReturn(0);

        ApprovalRequest request = requestWithResumeEnvelope();
        assertDoesNotThrow(() -> executor.execute(request));

        verify(engine, never()).resume(any(), any(), any(), any(), any(), any(), any());
        assertTrue(
                request.getExecutionResult().contains("claimed by a concurrent decision"),
                "the no-op must be explained on the request, got: " + request.getExecutionResult());
    }

    @Test
    void approveOnAnAlreadyTerminalRunIsAQuietNoOpWithoutEvenAttemptingTheClaim() {
        WorkflowRun completed = runWithStatus(WorkflowRunStatus.COMPLETED);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(completed));

        ApprovalRequest request = requestWithResumeEnvelope();
        assertDoesNotThrow(() -> executor.execute(request));

        verify(runRepository, never()).compareAndSetStatus(any(), any(), any());
        verify(engine, never()).resume(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void payloadWithoutAResumeEnvelopeDeclinesLoudlyWithoutTouchingTheRun() {
        ApprovalRequest request = legacyRequest();

        WorkflowApprovalResumeException thrown =
                assertThrows(
                        WorkflowApprovalResumeException.class, () -> executor.execute(request));

        // A pre-resume-support row won't fix itself on RetryFailedActionsJob's
        // timer - it must be a terminal, explained failure, and the run must
        // stay AWAITING_APPROVAL (untouched) rather than being claimed.
        assertFalse(thrown instanceof RetryableActionFailure);
        assertTrue(
                thrown.getMessage().contains("predates resume support"),
                "message must say why, got: " + thrown.getMessage());
        verify(runRepository, never()).compareAndSetStatus(any(), any(), any());
        verify(runRepository, never()).save(any());
    }

    @Test
    void resumeFailureAfterTheClaimTerminalizesTheRunInsteadOfStrandingItRunning()
            throws ReflectiveOperationException {
        stubbedDefinition();
        WorkflowRun awaiting = runWithStatus(WorkflowRunStatus.AWAITING_APPROVAL);
        WorkflowRun claimed = runWithStatus(WorkflowRunStatus.RUNNING);
        when(runRepository.findById(RUN_ID))
                .thenReturn(Optional.of(awaiting))
                .thenReturn(Optional.of(claimed));
        when(runRepository.compareAndSetStatus(
                        RUN_ID, WorkflowRunStatus.AWAITING_APPROVAL, WorkflowRunStatus.RUNNING))
                .thenReturn(1);
        when(engine.resume(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("engine exploded"));

        assertThrows(
                IllegalStateException.class, () -> executor.execute(requestWithResumeEnvelope()));

        // Best-effort terminalization: the claimed run must not sit RUNNING
        // until the recovery reaper mislabels it hours later.
        assertEquals(WorkflowRunStatus.FAILED, claimed.getStatus());
        assertTrue(claimed.getErrorMessage().contains("engine exploded"));
        verify(runRepository).save(claimed);
    }

    // --- reject -------------------------------------------------------------

    @Test
    void rejectionMarksTheAwaitingRunFailedWithTheDeciderAndReason() {
        when(runRepository.compareAndSetStatus(
                        RUN_ID, WorkflowRunStatus.AWAITING_APPROVAL, WorkflowRunStatus.FAILED))
                .thenReturn(1);
        WorkflowRun run = runWithStatus(WorkflowRunStatus.AWAITING_APPROVAL);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        executor.onRejected(requestWithResumeEnvelope(), "admin-1", "numbers look wrong");

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertTrue(run.getErrorMessage().contains("admin-1"));
        assertTrue(run.getErrorMessage().contains("numbers look wrong"));
        assertTrue(
                run.getTraceJson().contains("Approval rejected by admin-1"),
                "the rejection must be visible in the trace, got: " + run.getTraceJson());
        verify(runRepository).save(run);
        verify(eventPublisher).publish(eq("workflow.run.failed"), eq(RUN_ID.toString()), any());
    }

    @Test
    void rejectionLosingTheClaimRaceChangesNothing() {
        when(runRepository.compareAndSetStatus(
                        RUN_ID, WorkflowRunStatus.AWAITING_APPROVAL, WorkflowRunStatus.FAILED))
                .thenReturn(0);

        assertDoesNotThrow(() -> executor.onRejected(requestWithResumeEnvelope(), "admin-1", "no"));

        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void rejectionOfARowWithNoRunReferenceIsAQuietNoOp() {
        // e.g. a hand-recorded or corrupted payload - rejection of the
        // REQUEST already stands (recorded by the gate service before this
        // hook runs); there is simply no run to fail.
        ApprovalRequest request = new ApprovalRequest(ACTION_TYPE, "summary", "{}", "user-1");

        assertDoesNotThrow(() -> executor.onRejected(request, "admin-1", "no"));

        verify(runRepository, never()).compareAndSetStatus(any(), any(), any());
    }

    // --- rejection needs no resume envelope (legacy rows still get stopped) --

    @Test
    void rejectionWorksOnLegacyRowsThatCouldNeverResume() {
        // Approve on a legacy row declines (no recorded input to resume
        // with) - but reject only needs the runId, which legacy rows DO
        // carry, so the stuck run still gets its honest terminal state.
        when(runRepository.compareAndSetStatus(
                        RUN_ID, WorkflowRunStatus.AWAITING_APPROVAL, WorkflowRunStatus.FAILED))
                .thenReturn(1);
        WorkflowRun run = runWithStatus(WorkflowRunStatus.AWAITING_APPROVAL);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        executor.onRejected(legacyRequest(), "admin-1", null);

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertTrue(run.getErrorMessage().contains("admin-1"));
    }

    // --- family routing ------------------------------------------------------

    @Test
    void supportsTheWholeWorkflowNodeFamilyButNeverTheBarePrefix() {
        assertTrue(executor.supports("workflow.node.approval"));
        assertTrue(executor.supports("workflow.node.agent"));
        assertFalse(executor.supports("workflow.node."));
        assertFalse(executor.supports("mcp_tool.send_message"));
        assertFalse(executor.supports(null));
        List.of("workflow.node", "workflow").forEach(t -> assertFalse(executor.supports(t)));
    }
}
