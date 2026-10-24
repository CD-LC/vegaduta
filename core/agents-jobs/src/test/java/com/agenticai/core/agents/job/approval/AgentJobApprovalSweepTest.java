package com.agenticai.core.agents.job.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobExecutor;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestExecutionRecordedEvent;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The out-of-band half of PR-5, and the half that carries the guarantees.
 *
 * <p>There is deliberately NO in-process fast path from "a human clicked
 * approve" to "the job resumes" - the {@code mcp_tool.} action-type family is
 * already owned by {@code AgentToolCallApprovalExecutor} and
 * {@code ApprovalActionExecutorRegistry#find} resolves exactly one executor per
 * action type, so a second one would either never run or would stop the approved
 * tool from replaying. That makes this tick the ONLY resume mechanism, which is
 * why {@link #anApprovedRequestHandsTheJobBackToAWorkerWithNoFastPathInvolved()}
 * is the acceptance test for "approving it completes the work" rather than a
 * belt-and-braces extra.
 *
 * <p>Every write asserted below is a GUARDED conditional UPDATE
 * ({@code failIfAwaitingApproval}, {@code cancelIfAwaitingApproval}), which is
 * what makes the sweep safe against a resumed worker landing in the same
 * instant: whichever write lands first wins and the other sees rowcount 0.
 */
class AgentJobApprovalSweepTest {

    private AgentJobApprovalLookup lookup;
    private AgentJobExecutor executor;
    private AgentJobProperties properties;
    private TenantSchemaIterator schemaIterator;
    private PgAdvisoryJobLock jobLock;

    @BeforeEach
    void setUp() {
        lookup = mock(AgentJobApprovalLookup.class);
        executor = mock(AgentJobExecutor.class);
        properties = AgentJobApprovalTestSupport.properties();

        schemaIterator = mock(TenantSchemaIterator.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept("tenant_acme");
            return null;
        }).when(schemaIterator).forEachActiveTenantSchema(any());

        jobLock = mock(PgAdvisoryJobLock.class);
        when(jobLock.tryRunExclusive(anyString(), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        });
    }

    @Test
    void anIgnoredApprovalTimesOutWithAClearStatus() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().minusSeconds(60));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        overdue(repository, parked);

        sweep(repository).sweepParkedJobs();

        verify(repository).failIfAwaitingApproval(
            eq(parked.getId()), eq(AgentJobApprovalSweep.ERROR_APPROVAL_TIMEOUT), anyString(), any(Instant.class));
        assertEquals(AgentJobStatus.FAILED, parked.getStatus());
        assertEquals(AgentJobApprovalSweep.ERROR_APPROVAL_TIMEOUT, parked.getErrorCode());
        // "with a clear status" is the acceptance wording: an operator must be
        // able to tell an un-answered approval from a crashed job.
        assertTrue(parked.getErrorMessage().contains("No human decided"));
    }

    @Test
    void aJobPastItsParkCeilingFailsWithParkLimitRatherThanTimeout() {
        // park_count 4 against the default ceiling of 3. The detector produces
        // this shape on purpose: it refuses to park a job again by parking it
        // with an already-dead deadline, so the sweep - the single writer of a
        // parked job's terminal status - is what actually fails it.
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().minusSeconds(1));
        for (int i = 0; i < 3; i++) {
            parked.resumeFromApproval("host-a:1", Instant.now());
            parked.parkForApproval(Instant.now(), Instant.now().minusSeconds(1));
        }
        assertEquals(4, parked.getParkCount());
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        overdue(repository, parked);

        sweep(repository).sweepParkedJobs();

        // Reporting this as a TIMEOUT would send an operator looking for a human
        // who never existed, instead of at an agent stuck in an approve-loop.
        verify(repository).failIfAwaitingApproval(
            eq(parked.getId()), eq(AgentJobApprovalSweep.ERROR_APPROVAL_PARK_LIMIT), anyString(), any(Instant.class));
        assertEquals(AgentJobApprovalSweep.ERROR_APPROVAL_PARK_LIMIT, parked.getErrorCode());
    }

    @Test
    void aRejectedApprovalFailsTheJobRatherThanLeavingItParked() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        stillParked(repository, parked);
        when(lookup.findByRequestedByAndStatusOrderByCreatedAtAsc(label(parked), ApprovalStatus.REJECTED))
            .thenReturn(List.of(AgentJobApprovalTestSupport.rejectedRequestFor(parked)));

        sweep(repository).sweepParkedJobs();

        verify(repository).failIfAwaitingApproval(
            eq(parked.getId()), eq(AgentJobApprovalSweep.ERROR_APPROVAL_REJECTED), anyString(), any(Instant.class));
        assertEquals(AgentJobStatus.FAILED, parked.getStatus());
        verify(executor, never()).tryDispatch(any(UUID.class), anyString());
    }

    /**
     * "Approving it completes the work", proved without any executor fast path
     * existing at all - which is also the restart case: a decision made while
     * this replica was down is picked up by the first tick after it comes back,
     * because the state lives in the row, not in a listener.
     */
    @Test
    void anApprovedRequestHandsTheJobBackToAWorkerWithNoFastPathInvolved() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        stillParked(repository, parked);
        // Nothing PENDING and nothing REJECTED: the human decided.
        when(executor.tryDispatch(parked.getId(), parked.getTenantId())).thenReturn(true);

        sweep(repository).sweepParkedJobs();

        verify(executor).tryDispatch(parked.getId(), parked.getTenantId());
        // The sweep never writes the status itself - AgentJobExecution's
        // resumeClaim owns AWAITING_APPROVAL -> RUNNING, so exactly one code
        // path transitions a job to RUNNING.
        verify(repository, never()).failIfAwaitingApproval(any(), anyString(), anyString(), any(Instant.class));
        assertEquals(AgentJobStatus.AWAITING_APPROVAL, parked.getStatus());
    }

    @Test
    void aStillPendingApprovalLeavesTheJobParkedAndCostsOneQuery() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        stillParked(repository, parked);
        when(lookup.findByRequestedByAndStatusOrderByCreatedAtAsc(label(parked), ApprovalStatus.PENDING))
            .thenReturn(List.of(AgentJobApprovalTestSupport.pendingRequestFor(parked)));

        sweep(repository).sweepParkedJobs();

        assertEquals(AgentJobStatus.AWAITING_APPROVAL, parked.getStatus());
        verify(executor, never()).tryDispatch(any(UUID.class), anyString());
        // The normal state of a parked job has to stay cheap: PENDING short-
        // circuits before the rejection query runs.
        verify(lookup, never())
            .findByRequestedByAndStatusOrderByCreatedAtAsc(anyString(), eq(ApprovalStatus.REJECTED));
    }

    @Test
    void aCancelRequestedWhileTheJobWasParkingIsHonouredByTheSweep() {
        // The race the executor's heartbeat cannot cover: a parked job holds no
        // worker, so nothing is watching cancel_requested for it. Before this
        // branch the row sat AWAITING_APPROVAL with the flag set until its
        // 24-hour deadline, holding its schedule's overlap guard shut.
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        parked.markCancelRequested("carol@example.com", Instant.now());
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        stillParked(repository, parked);

        sweep(repository).sweepParkedJobs();

        verify(repository).cancelIfAwaitingApproval(eq(parked.getId()), eq("carol@example.com"), any(Instant.class));
        assertEquals(AgentJobStatus.CANCELLED, parked.getStatus());
        verify(executor, never()).tryDispatch(any(UUID.class), anyString());
    }

    @Test
    void oneBadRowDoesNotStopTheRestOfTheSweep() {
        AgentJob broken = AgentJobApprovalTestSupport.parkedJob(Instant.now().minusSeconds(60));
        AgentJob healthy = AgentJobApprovalTestSupport.parkedJob(Instant.now().minusSeconds(60));
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.findByStatusAndApprovalDeadlineAtBefore(
            eq(AgentJobStatus.AWAITING_APPROVAL), any(Instant.class)))
            .thenReturn(List.of(broken, healthy));
        when(repository.failIfAwaitingApproval(eq(broken.getId()), anyString(), anyString(), any(Instant.class)))
            .thenThrow(new IllegalStateException("connection reset"));
        when(repository.failIfAwaitingApproval(eq(healthy.getId()), anyString(), anyString(), any(Instant.class)))
            .thenReturn(1);

        sweep(repository).sweepParkedJobs();

        // Without the per-row try/catch, one transient blip would leave every
        // other overdue park in the tenant waiting another full interval - or,
        // for a repeatably bad row, forever.
        verify(repository).failIfAwaitingApproval(
            eq(healthy.getId()), eq(AgentJobApprovalSweep.ERROR_APPROVAL_TIMEOUT), anyString(), any(Instant.class));
    }

    @Test
    void theSweepRunsUnderItsOwnAdvisoryLockAndIteratesEveryTenantSchema() {
        AgentJobRepository repository = mock(AgentJobRepository.class);

        sweep(repository).sweepParkedJobs();

        // A distinct lock name (two features sharing one would silently
        // serialise against each other), and the tenant iteration without which
        // every query would read the empty `public` schema - the 2026-08-10
        // document-generation incident.
        verify(jobLock).tryRunExclusive(eq(AgentJobApprovalSweep.APPROVAL_SWEEP_LOCK), any(Runnable.class));
        verify(schemaIterator).forEachActiveTenantSchema(any());
    }

    // -------------------------------------------------------------------- helpers

    private AgentJobApprovalSweep sweep(AgentJobRepository repository) {
        return new AgentJobApprovalSweep(
            repository, lookup, properties, schemaIterator, jobLock, new SimpleMeterRegistry(),
            providerOf(executor), gateProvider());
    }

    /** The row is past its approval deadline, so only the expiry pass sees it. */
    private static void overdue(AgentJobRepository repository, AgentJob job) {
        when(repository.findByStatusAndApprovalDeadlineAtBefore(
            eq(AgentJobStatus.AWAITING_APPROVAL), any(Instant.class)))
            .thenReturn(List.of(job));
    }

    /**
     * The row is within its deadline, so only the settle pass sees it. Mirrors
     * production ordering: the expiry pass commits its own UPDATE before the
     * settle pass queries, so a row terminalised by the first is simply no
     * longer AWAITING_APPROVAL when the second reads.
     */
    private static void stillParked(AgentJobRepository repository, AgentJob job) {
        when(repository.findTop50ByStatusOrderByCreatedAtAsc(AgentJobStatus.AWAITING_APPROVAL))
            .thenReturn(List.of(job));
    }

    private static String label(AgentJob job) {
        return AgentJobSession.requestedByLabel(job.getAgentId(), job.getId());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AgentJobExecutor> providerOf(AgentJobExecutor executor) {
        ObjectProvider<AgentJobExecutor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(executor);
        return provider;
    }

    /**
     * The sweep takes the approval gate as an {@code ObjectProvider} purely to
     * break the bean cycle through {@code ApprovalActionExecutorRegistry} (see
     * the constructor javadoc on {@code AgentJobApprovalSweep}). Tests that do
     * not care pass the unstubbed default ({@code getIfAvailable()} null, the
     * same shape as a slim context with no gate bean); the orphaned-request
     * tests pass a real mock through {@link #gateProvider(HumanApprovalGateService)}.
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<HumanApprovalGateService> gateProvider() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<HumanApprovalGateService> gateProvider(HumanApprovalGateService gate) {
        ObjectProvider<HumanApprovalGateService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gate);
        return provider;
    }

    private AgentJobApprovalSweep sweep(AgentJobRepository repository, HumanApprovalGateService gate) {
        return new AgentJobApprovalSweep(
            repository, lookup, properties, schemaIterator, jobLock, new SimpleMeterRegistry(),
            providerOf(executor), gateProvider(gate));
    }

    /** Sets {@code decidedAt} on a fixture; the entity mutators only ever stamp {@code Instant.now()}. */
    private static void decidedAt(ApprovalRequest request, Instant when) {
        try {
            java.lang.reflect.Field field = ApprovalRequest.class.getDeclaredField("decidedAt");
            field.setAccessible(true);
            field.set(request, when);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------- terminalisation closes both halves (M6)

    /**
     * Terminalising a parked job must also un-PENDING its blocking request -
     * otherwise a tenant-admin approving the orphan weeks later REPLAYS a real,
     * non-idempotent tool call for a job that is already FAILED.
     */
    @Test
    void timingOutAParkedJobRejectsItsOrphanedPendingRequest() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().minusSeconds(60));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        overdue(repository, parked);
        ApprovalRequest orphan = AgentJobApprovalTestSupport.pendingRequestFor(parked);
        when(lookup.findByRequestedByAndStatusOrderByCreatedAtAsc(label(parked), ApprovalStatus.PENDING))
            .thenReturn(List.of(orphan));
        HumanApprovalGateService gate = mock(HumanApprovalGateService.class);

        sweep(repository, gate).sweepParkedJobs();

        assertEquals(AgentJobStatus.FAILED, parked.getStatus());
        // Through the OWNING service, never a direct table write - approve()
        // then throws on the now-decided row, which is the whole point.
        verify(gate).reject(eq(orphan.getId()), anyString(), contains("already terminal"));
    }

    @Test
    void cancellingAParkedJobRejectsItsOrphanedPendingRequest() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        parked.markCancelRequested("carol@example.com", Instant.now());
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        stillParked(repository, parked);
        ApprovalRequest orphan = AgentJobApprovalTestSupport.pendingRequestFor(parked);
        when(lookup.findByRequestedByAndStatusOrderByCreatedAtAsc(label(parked), ApprovalStatus.PENDING))
            .thenReturn(List.of(orphan));
        HumanApprovalGateService gate = mock(HumanApprovalGateService.class);

        sweep(repository, gate).sweepParkedJobs();

        assertEquals(AgentJobStatus.CANCELLED, parked.getStatus());
        verify(gate).reject(eq(orphan.getId()), anyString(), anyString());
    }

    // ------------------------------------------------- decided but still settling (M2 + M3)

    /**
     * {@code EXECUTION_FAILED} with {@code nextRetryAt} set means "failed,
     * RETRY SCHEDULED" - {@code RetryFailedActionsJob} will run the gated tool
     * again. Resuming now would let the resumed turn re-invoke the same tool
     * concurrently with that retry, and nothing dedupes tool calls.
     */
    @Test
    void aScheduledRetryOfTheApprovedActionHoldsTheResume() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        stillParked(repository, parked);
        ApprovalRequest retrying = AgentJobApprovalTestSupport.pendingRequestFor(parked);
        retrying.markApproved("admin@example.com");
        retrying.scheduleRetry("provider 503", Instant.now().plusSeconds(30));
        when(lookup.findTop20ByRequestedByOrderByCreatedAtDesc(label(parked)))
            .thenReturn(List.of(retrying));

        sweep(repository).sweepParkedJobs();

        verify(executor, never()).tryDispatch(any(UUID.class), anyString());
        assertEquals(AgentJobStatus.AWAITING_APPROVAL, parked.getStatus());
    }

    /**
     * approve() commits the decision FIRST and replays the tool afterwards,
     * outside any transaction. A sweep tick inside that window must wait -
     * dispatching would bill a turn that re-asks for the same gate, or race the
     * replay into a duplicate side effect.
     */
    @Test
    void aFreshlyApprovedRequestWhoseReplayHasNotFinishedHoldsTheResume() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        stillParked(repository, parked);
        ApprovalRequest justApproved = AgentJobApprovalTestSupport.pendingRequestFor(parked);
        justApproved.markApproved("admin@example.com");
        // decidedAt = now, executionResult still null: the replay is running.
        when(lookup.findTop20ByRequestedByOrderByCreatedAtDesc(label(parked)))
            .thenReturn(List.of(justApproved));

        sweep(repository).sweepParkedJobs();

        verify(executor, never()).tryDispatch(any(UUID.class), anyString());
    }

    /**
     * ...and the wait is BOUNDED: past the grace the sweep resumes anyway,
     * because the replay may have died with its JVM and waiting forever would
     * strand the job. The residual (a replay that is merely very slow) is a
     * documented trade, not an accident.
     */
    @Test
    void anApprovedRequestPastTheReplayGraceResumesEvenWithoutARecordedResult() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        stillParked(repository, parked);
        ApprovalRequest staleApproval = AgentJobApprovalTestSupport.pendingRequestFor(parked);
        staleApproval.markApproved("admin@example.com");
        decidedAt(staleApproval, Instant.now().minusSeconds(600));
        when(lookup.findTop20ByRequestedByOrderByCreatedAtDesc(label(parked)))
            .thenReturn(List.of(staleApproval));
        when(executor.tryDispatch(parked.getId(), parked.getTenantId())).thenReturn(true);

        sweep(repository).sweepParkedJobs();

        verify(executor).tryDispatch(parked.getId(), parked.getTenantId());
    }

    /**
     * {@code app.agent-jobs.approval.replay-grace} replaces the old 90-second constant: an approval decided
     * 100 s ago is past the default grace (resume), but inside a configured 5-minute grace (hold).
     */
    @Test
    void theReplayGraceIsConfigurable() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        stillParked(repository, parked);
        ApprovalRequest approved = AgentJobApprovalTestSupport.pendingRequestFor(parked);
        approved.markApproved("admin@example.com");
        decidedAt(approved, Instant.now().minusSeconds(100));
        when(lookup.findTop20ByRequestedByOrderByCreatedAtDesc(label(parked))).thenReturn(List.of(approved));
        when(executor.tryDispatch(parked.getId(), parked.getTenantId())).thenReturn(true);

        properties.getApproval().setReplayGrace(java.time.Duration.ofMinutes(5));
        sweep(repository).sweepParkedJobs();
        verify(executor, never()).tryDispatch(any(UUID.class), anyString());

        properties.getApproval().setReplayGrace(java.time.Duration.ofSeconds(90));
        sweep(repository).sweepParkedJobs();
        verify(executor).tryDispatch(parked.getId(), parked.getTenantId());
    }

    /**
     * A request that expired undecided ({@code app.approvals.expiry}) is stored as a rejection by the
     * expiry actor. The job must fail as a TIMEOUT with the expiry message, not as "a human rejected".
     */
    @Test
    void anExpiredApprovalFailsTheJobAsATimeoutNotAsAHumanRejection() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        stillParked(repository, parked);
        ApprovalRequest expired = AgentJobApprovalTestSupport.pendingRequestFor(parked);
        expired.markExpired("nobody decided in 24 hours");
        when(lookup.findByRequestedByAndStatusOrderByCreatedAtAsc(label(parked), ApprovalStatus.REJECTED))
            .thenReturn(List.of(expired));

        sweep(repository).sweepParkedJobs();

        verify(repository).failIfAwaitingApproval(
            eq(parked.getId()), eq(AgentJobApprovalSweep.ERROR_APPROVAL_TIMEOUT), anyString(), any(Instant.class));
        assertEquals(AgentJobApprovalSweep.ERROR_APPROVAL_TIMEOUT, parked.getErrorCode());
        assertTrue(parked.getErrorMessage().contains("expired"), parked.getErrorMessage());
        assertTrue(parked.getErrorMessage().contains(label(parked)), "the {label} placeholder is filled in");
        verify(executor, never()).tryDispatch(any(UUID.class), anyString());
    }

    /** A mix of an expired and a human-rejected request is still a human rejection. */
    @Test
    void aHumanRejectionAmongExpiredRequestsStillFailsTheJobAsRejected() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        stillParked(repository, parked);
        ApprovalRequest expired = AgentJobApprovalTestSupport.pendingRequestFor(parked);
        expired.markExpired("nobody decided");
        when(lookup.findByRequestedByAndStatusOrderByCreatedAtAsc(label(parked), ApprovalStatus.REJECTED))
            .thenReturn(List.of(expired, AgentJobApprovalTestSupport.rejectedRequestFor(parked)));

        sweep(repository).sweepParkedJobs();

        verify(repository).failIfAwaitingApproval(
            eq(parked.getId()), eq(AgentJobApprovalSweep.ERROR_APPROVAL_REJECTED), anyString(), any(Instant.class));
    }

    /** The intended happy path: approved, replay finished, result recorded - resume. */
    @Test
    void anApprovedRequestWithARecordedResultResumesImmediately() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        stillParked(repository, parked);
        ApprovalRequest settled = AgentJobApprovalTestSupport.pendingRequestFor(parked);
        settled.markApproved("admin@example.com");
        settled.recordExecutionResult("{\"delivered\":true}");
        when(lookup.findTop20ByRequestedByOrderByCreatedAtDesc(label(parked)))
            .thenReturn(List.of(settled));
        when(executor.tryDispatch(parked.getId(), parked.getTenantId())).thenReturn(true);

        sweep(repository).sweepParkedJobs();

        verify(executor).tryDispatch(parked.getId(), parked.getTenantId());
    }

    // ------------------------------------------- event-driven fast path (PR: instant resume)

    /**
     * (b) The listener resumes WITHOUT waiting for {@code REPLAY_GRACE} (90s)
     * or the next poll tick - {@code onApprovalExecutionRecorded} is called
     * directly here, with no {@link #sweepParkedJobs} tick involved at all,
     * and {@code decidedAt} is deliberately set to "just now" (well inside the
     * grace window) to prove it is {@code executionResult} being non-null,
     * not elapsed time, that lets this resume proceed immediately - exactly
     * {@link #anApprovedRequestWithARecordedResultResumesImmediately}'s
     * scenario, reached the fast way instead of by a tick.
     */
    @Test
    void theListenerResumesImmediatelyWithoutWaitingForTheGraceWindowOrThePollTick() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        ApprovalRequest settled = AgentJobApprovalTestSupport.pendingRequestFor(parked);
        settled.markApproved("admin@example.com");
        settled.recordExecutionResult("{\"delivered\":true}");
        when(lookup.findTop20ByRequestedByOrderByCreatedAtDesc(label(parked)))
            .thenReturn(List.of(settled));
        when(executor.tryDispatch(parked.getId(), parked.getTenantId())).thenReturn(true);

        sweep(repository).onApprovalExecutionRecorded(
            new ApprovalRequestExecutionRecordedEvent(this, settled.getId(), label(parked)));

        verify(executor).tryDispatch(parked.getId(), parked.getTenantId());
        // The lock/tenant-iteration machinery the poll needs was never touched -
        // this really is a different path, not the poll in disguise.
        verify(jobLock, never()).tryRunExclusive(anyString(), any(Runnable.class));
    }

    /**
     * The event's {@code requestedBy} does not resolve to a job (a channel
     * send, a workflow-node approval, a console tool-call gate, ...) - by far
     * the common case, since most approval requests are never job-related at
     * all. Must be a cheap no-op: no repository lookup, no dispatch.
     */
    @Test
    void theListenerIgnoresEventsForApprovalRequestsThatAreNotJobRelated() {
        AgentJobRepository repository = mock(AgentJobRepository.class);

        sweep(repository).onApprovalExecutionRecorded(
            new ApprovalRequestExecutionRecordedEvent(this, UUID.randomUUID(), "someone@example.com"));

        verify(repository, never()).findById(any(UUID.class));
        verify(executor, never()).tryDispatch(any(UUID.class), anyString());
    }

    /**
     * Same race the class javadoc documents: whichever of the listener and the
     * next poll tick gets there first wins, and the other sees the job is no
     * longer {@code AWAITING_APPROVAL} and quietly does nothing - no double
     * dispatch. Simulated here by having the job already RUNNING (as if a
     * prior tick or an earlier event had already resumed it) when the event
     * arrives.
     */
    @Test
    void theListenerIsANoOpIfTheJobIsNoLongerAwaitingApproval() {
        AgentJob alreadyResumed = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        alreadyResumed.resumeFromApproval("host-a:1", Instant.now());
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(alreadyResumed);

        sweep(repository).onApprovalExecutionRecorded(new ApprovalRequestExecutionRecordedEvent(
            this, UUID.randomUUID(), label(alreadyResumed)));

        verify(executor, never()).tryDispatch(any(UUID.class), anyString());
    }

    /**
     * (c) The poll remains a fully independent fallback: this resumes the
     * exact same settled-approval scenario as the listener test above purely
     * via {@link #sweepParkedJobs}, WITHOUT {@code onApprovalExecutionRecorded}
     * ever being called - simulating an event that was missed entirely (e.g.
     * a replica restart between the write and the publish, or a listener
     * exception swallowed and logged). Nothing about resuming depends on the
     * event having fired.
     */
    @Test
    void thePollStillResumesTheJobWhenNoEventEverArrives() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(parked);
        stillParked(repository, parked);
        ApprovalRequest settled = AgentJobApprovalTestSupport.pendingRequestFor(parked);
        settled.markApproved("admin@example.com");
        settled.recordExecutionResult("{\"delivered\":true}");
        when(lookup.findTop20ByRequestedByOrderByCreatedAtDesc(label(parked)))
            .thenReturn(List.of(settled));
        when(executor.tryDispatch(parked.getId(), parked.getTenantId())).thenReturn(true);

        // No onApprovalExecutionRecorded call anywhere in this test.
        sweep(repository).sweepParkedJobs();

        verify(executor).tryDispatch(parked.getId(), parked.getTenantId());
    }
}
