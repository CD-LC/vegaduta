package com.agenticai.core.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.rl.feedback.FeedbackService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

/**
 * A decision is committed before the approved action runs, so a process that died in between left an approved request
 * that nothing ran and nothing reported. Real gate, real recorder and real entity over a mocked repository: one caller
 * runs the action, a never-started one is recovered once, a started-but-unfinished one is reported and never re-run.
 */
class ApprovalRecoveryTest {

    private ApprovalRequestRepository repository;
    private AuditLogService audit;
    private ApprovalActionExecutor executor;
    private ApprovalOutcomePort outcomes;
    private ApprovalRequestRecorder recorder;
    private HumanApprovalGateService gate;
    private ApprovalRecoveryProperties properties;
    private ApprovalRecoveryJob job;

    @BeforeEach
    void setUp() {
        repository = mock(ApprovalRequestRepository.class);
        audit = mock(AuditLogService.class);
        executor = mock(ApprovalActionExecutor.class);
        outcomes = mock(ApprovalOutcomePort.class);
        ApprovalActionExecutorRegistry registry = mock(ApprovalActionExecutorRegistry.class);
        when(registry.find(anyString())).thenReturn(Optional.of(executor));
        when(repository.save(any(ApprovalRequest.class))).thenAnswer(call -> call.getArgument(0));
        recorder = new ApprovalRequestRecorder(
            repository, audit, mock(ApplicationEventPublisher.class), mock(WorkflowEventPublisher.class));
        gate = new HumanApprovalGateService(
            mock(ApprovalPolicyService.class), repository, registry, recorder, mock(FeedbackService.class),
            new ObjectMapper(), 5, 30, 3600);
        gate.setOutcomePort(outcomes);

        TenantSchemaIterator schemas = mock(TenantSchemaIterator.class);
        doAnswer(call -> {
            Consumer<String> perSchema = call.getArgument(0);
            perSchema.accept("t_one");
            return null;
        }).when(schemas).forEachActiveTenantSchema(any());
        properties = new ApprovalRecoveryProperties();
        job = new ApprovalRecoveryJob(properties, schemas, repository, gate, mock(PgAdvisoryJobLock.class));
    }

    private ApprovalRequest approved() throws Exception {
        ApprovalRequest request = new ApprovalRequest("mcp_tool.send", "send it", "{}", "agent:a:session:s");
        Field id = ApprovalRequest.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(request, UUID.randomUUID());
        request.markApproved("alice");
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
        when(repository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        return request;
    }

    private static void decidedAgo(ApprovalRequest request, Duration ago) throws Exception {
        Field decidedAt = ApprovalRequest.class.getDeclaredField("decidedAt");
        decidedAt.setAccessible(true);
        decidedAt.set(request, Instant.now().minus(ago));
    }

    private static void startedAgo(ApprovalRequest request, Duration ago) throws Exception {
        Field started = ApprovalRequest.class.getDeclaredField("executionStartedAt");
        started.setAccessible(true);
        started.set(request, Instant.now().minus(ago));
    }

    // ------------------------------------------------------------------ the claim

    @Test
    void aLiveDecisionClaimsTheActionRunsItOnceAndMarksItFinished() throws Exception {
        ApprovalRequest request = new ApprovalRequest("mcp_tool.send", "send it", "{}", "agent:a:session:s");
        Field id = ApprovalRequest.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(request, UUID.randomUUID());
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
        when(repository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        gate.approve(request.getId(), "alice");

        verify(executor).execute(request);
        assertThat(request.getExecutionStartedAt()).isNotNull();
        assertThat(request.getExecutionCompletedAt()).isNotNull();
        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void anActionAlreadyClaimedIsNotRunAgain() throws Exception {
        ApprovalRequest request = approved();
        startedAgo(request, Duration.ofSeconds(1));

        gate.runUnstartedApproval(request);

        verify(executor, never()).execute(any());
    }

    // ------------------------------------------------------------------ never started

    @Test
    void anApprovalNobodyStartedIsRunOnceBySweep() throws Exception {
        ApprovalRequest request = approved();
        decidedAgo(request, Duration.ofMinutes(10));
        when(repository.findApprovedNeverStarted(any(Instant.class), any(Pageable.class))).thenReturn(List.of(request));
        when(repository.findApprovedStartedNeverFinished(any(Instant.class), any(Pageable.class))).thenReturn(List.of());

        int handled = job.sweep(Instant.now());

        assertThat(handled).isEqualTo(1);
        verify(executor).execute(request);
        assertThat(request.getExecutionCompletedAt()).isNotNull();
        verify(outcomes).afterDecision(request, ApprovalOutcomePort.Outcome.COMPLETED, null);

        // A second sweep that still sees the same row (a stale read) cannot run it again.
        job.sweep(Instant.now());
        verify(executor).execute(request);
    }

    @Test
    void anApprovalTooOldToActOnIsClosedNotRun() throws Exception {
        ApprovalRequest request = approved();
        decidedAgo(request, Duration.ofHours(5));
        when(repository.findApprovedNeverStarted(any(Instant.class), any(Pageable.class))).thenReturn(List.of(request));
        when(repository.findApprovedStartedNeverFinished(any(Instant.class), any(Pageable.class))).thenReturn(List.of());

        job.sweep(Instant.now());

        verify(executor, never()).execute(any());
        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.DEAD_LETTERED);
        assertThat(request.getExecutionError()).isEqualTo(ApprovalRecoveryJob.STALE_REASON);
        assertThat(request.getExecutionCompletedAt()).isNotNull();
        verify(audit).record(
            ApprovalRequestRecorder.RECOVERY_ACTOR, "mcp_tool.send", "approval_request",
            request.getId().toString(), AuditOutcome.FAILURE, ApprovalRecoveryJob.STALE_REASON);
        verify(outcomes).afterDecision(request, ApprovalOutcomePort.Outcome.FAILED, ApprovalRecoveryJob.STALE_REASON);
    }

    @Test
    void aRecoveredActionThatFailsIsRecordedAndDoesNotStopTheBatch() throws Exception {
        ApprovalRequest failing = approved();
        ApprovalRequest fine = approved();
        decidedAgo(failing, Duration.ofMinutes(10));
        decidedAgo(fine, Duration.ofMinutes(10));
        doThrow(new IllegalStateException("boom")).when(executor).execute(failing);
        when(repository.findApprovedNeverStarted(any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(failing, fine));
        when(repository.findApprovedStartedNeverFinished(any(Instant.class), any(Pageable.class))).thenReturn(List.of());

        job.sweep(Instant.now());

        assertThat(failing.getStatus()).isEqualTo(ApprovalStatus.EXECUTION_FAILED);
        verify(executor).execute(fine);
        assertThat(fine.getExecutionCompletedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ started, end never recorded

    @Test
    void anActionThatStartedAndNeverFinishedIsReportedAndNeverRunAgain() throws Exception {
        ApprovalRequest request = approved();
        decidedAgo(request, Duration.ofHours(2));
        startedAgo(request, Duration.ofHours(1));
        when(repository.findApprovedNeverStarted(any(Instant.class), any(Pageable.class))).thenReturn(List.of());
        when(repository.findApprovedStartedNeverFinished(any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(request));

        int handled = job.sweep(Instant.now());

        assertThat(handled).isEqualTo(1);
        verify(executor, never()).execute(any());
        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.DEAD_LETTERED);
        assertThat(request.getExecutionError()).isEqualTo(ApprovalRecoveryJob.INTERRUPTED_REASON);
        verify(outcomes).afterDecision(request, ApprovalOutcomePort.Outcome.FAILED, ApprovalRecoveryJob.INTERRUPTED_REASON);

        // Closed: a second sweep, or a late finish from the original run, changes nothing.
        job.sweep(Instant.now());
        verify(outcomes).afterDecision(any(), any(), anyString());
        recorder.recordExecutionFinished(request);
        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.DEAD_LETTERED);
    }

    @Test
    void anActionThatFinishedWhileTheSweepWasLookingIsLeftAlone() throws Exception {
        ApprovalRequest request = approved();
        startedAgo(request, Duration.ofHours(1));
        request.markExecutionCompleted();
        when(repository.findApprovedNeverStarted(any(Instant.class), any(Pageable.class))).thenReturn(List.of());
        when(repository.findApprovedStartedNeverFinished(any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(request));

        int handled = job.sweep(Instant.now());

        assertThat(handled).isZero();
        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        verify(outcomes, never()).afterDecision(any(), any(), any());
    }

    @Test
    void anApprovalWithNoExecutorIsClosedSoTheSweepStopsFindingIt() throws Exception {
        ApprovalRequest request = approved();
        decidedAgo(request, Duration.ofMinutes(10));
        ApprovalActionExecutorRegistry empty = mock(ApprovalActionExecutorRegistry.class);
        when(empty.find(anyString())).thenReturn(Optional.empty());
        HumanApprovalGateService noExecutor = new HumanApprovalGateService(
            mock(ApprovalPolicyService.class), repository, empty, recorder, mock(FeedbackService.class),
            new ObjectMapper(), 5, 30, 3600);

        noExecutor.runUnstartedApproval(request);

        assertThat(request.getExecutionCompletedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ defaults

    @Test
    void theDefaultsRunALiveDecisionFirstAndBoundHowOldARecoveredOneMayBe() {
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getUnstartedGrace()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.getMaxRecoveryAge()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.getStartedStaleAfter()).isEqualTo(Duration.ofMinutes(30));
    }
}
