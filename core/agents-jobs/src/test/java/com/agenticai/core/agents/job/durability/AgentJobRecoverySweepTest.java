package com.agenticai.core.agents.job.durability;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobExecutor;
import com.agenticai.core.agents.job.AgentJobOrphanRecoveryPolicy;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobRunner;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The orphan sweep with PR-4's recovery policy actually wired in - the end-to-end shape of "a job
 * interrupted by a restart resumes or fails loudly; it never sits in RUNNING forever with nothing
 * executing it".
 *
 * <p>{@link ZeroWorkOrphanRecoveryPolicyTest} pins WHICH orphans may be recovered. This class pins
 * what the sweep then DOES with that verdict, and in particular the three things that must never
 * happen: a job running on this replica being touched at all, an orphan that did work being handed
 * back to a worker, and a lost guarded UPDATE leaving a row in limbo.
 *
 * <p>Assertions are on the GUARDED repository writes, never on {@code save} - that is what makes
 * both outcomes safe against a worker finishing normally in the same instant: whichever statement
 * lands first wins and the other sees rowcount 0.
 */
class AgentJobRecoverySweepTest {

    private AgentJobRepository repository;
    private AgentJobExecutor executor;
    private AgentJobProperties properties;
    private ToolCallLogRepository toolCallLogRepository;
    private TenantSchemaIterator schemaIterator;
    private PgAdvisoryJobLock jobLock;

    @BeforeEach
    void setUp() {
        repository = mock(AgentJobRepository.class);
        executor = mock(AgentJobExecutor.class);
        properties = DurabilityTestSupport.properties();
        toolCallLogRepository = mock(ToolCallLogRepository.class);
        when(toolCallLogRepository.countByAgentIdAndSessionId(any(UUID.class), anyString()))
                .thenReturn(0L);

        schemaIterator = mock(TenantSchemaIterator.class);
        doAnswer(
                        invocation -> {
                            invocation.getArgument(0, Consumer.class).accept("tenant_acme");
                            return null;
                        })
                .when(schemaIterator)
                .forEachActiveTenantSchema(any());

        jobLock = mock(PgAdvisoryJobLock.class);
        when(jobLock.tryRunExclusive(anyString(), any(Runnable.class)))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(1, Runnable.class).run();
                            return true;
                        });
    }

    @Test
    void aZeroWorkOrphanIsRequeuedRatherThanFailed() {
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();
        stale(orphan);
        when(executor.isActiveHere(orphan.getId())).thenReturn(false);
        when(repository.requeueIfStillRunning(eq(orphan.getId()), any(Instant.class)))
                .thenReturn(1);

        runnerWith(realPolicy()).reapOrphans();

        // RUNNING -> QUEUED, so the next dispatch tick hands it to a worker
        // again. Nothing was charged the first time, so nothing is charged twice.
        verify(repository).requeueIfStillRunning(eq(orphan.getId()), any(Instant.class));
        verify(repository, never())
                .failIfStillRunning(
                        eq(orphan.getId()), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void anOrphanThatHadAlreadyStartedATurnIsFailedLoudlyWithOrphaned() {
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();
        orphan.incrementTurn();
        stale(orphan);
        when(executor.isActiveHere(orphan.getId())).thenReturn(false);
        when(repository.failIfStillRunning(
                        any(UUID.class), anyString(), anyString(), any(Instant.class)))
                .thenReturn(1);

        runnerWith(realPolicy()).reapOrphans();

        // Loud and terminal: an error_code a client can branch on, not silence
        // and not a row left RUNNING forever.
        verify(repository)
                .failIfStillRunning(
                        eq(orphan.getId()), eq("ORPHANED"), anyString(), any(Instant.class));
        verify(repository, never()).requeueIfStillRunning(any(UUID.class), any(Instant.class));
    }

    @Test
    void anOrphanWithLoggedToolCallsIsNeverHandedBackToAWorker() {
        // The no-double-charge, no-repeated-side-effect assertion at the sweep
        // level. Tool side effects are not idempotent and there is no job-level
        // cost ledger to reconcile a second attempt against.
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();
        stale(orphan);
        when(executor.isActiveHere(orphan.getId())).thenReturn(false);
        when(toolCallLogRepository.countByAgentIdAndSessionId(
                        orphan.getAgentId(), orphan.getSessionId()))
                .thenReturn(3L);
        when(repository.failIfStillRunning(
                        any(UUID.class), anyString(), anyString(), any(Instant.class)))
                .thenReturn(1);

        runnerWith(realPolicy()).reapOrphans();

        verify(repository, never()).requeueIfStillRunning(any(UUID.class), any(Instant.class));
        verify(repository)
                .failIfStillRunning(
                        eq(orphan.getId()), eq("ORPHANED"), anyString(), any(Instant.class));
    }

    @Test
    void aJobStillRunningOnThisReplicaIsNeitherRequeuedNorFailedNorEvenAssessed() {
        // The multi-replica safety property. A slow tick, a GC pause or a
        // sibling replica's live work must never be reclaimed - a sweep that
        // kills live jobs is a worse bug than the one PR-4 fixes.
        AgentJob liveHere = DurabilityTestSupport.abandonedRunningJob();
        stale(liveHere);
        when(executor.isActiveHere(liveHere.getId())).thenReturn(true);
        AgentJobOrphanRecoveryPolicy policy = mock(AgentJobOrphanRecoveryPolicy.class);

        runnerWith(policy).reapOrphans();

        verify(policy, never()).decide(any(AgentJob.class));
        verify(repository, never()).requeueIfStillRunning(any(UUID.class), any(Instant.class));
        verify(repository, never())
                .failIfStillRunning(any(UUID.class), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void losingTheGuardedRequeueLeavesTheJobAloneAndNeverFailsIt() {
        // Rowcount 0 on a REQUEUE verdict: the row finished, another sweep got it, or a live worker
        // heartbeated since the sweep began (the lease). Failing it would double-kill a live
        // worker.
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();
        stale(orphan);
        when(executor.isActiveHere(orphan.getId())).thenReturn(false);
        when(repository.requeueIfStillRunning(eq(orphan.getId()), any(Instant.class)))
                .thenReturn(0);

        runnerWith(realPolicy()).reapOrphans();

        verify(repository).requeueIfStillRunning(eq(orphan.getId()), any(Instant.class));
        verify(repository, never())
                .failIfStillRunning(any(UUID.class), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void anOrphanThePolicyFlagsForReviewIsFailedWithTheReviewCodeAndNeverRequeued() {
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();
        stale(orphan);
        when(executor.isActiveHere(orphan.getId())).thenReturn(false);
        when(repository.failIfStillRunning(
                        any(UUID.class), anyString(), anyString(), any(Instant.class)))
                .thenReturn(1);
        AgentJobOrphanRecoveryPolicy policy =
                new AgentJobOrphanRecoveryPolicy() {
                    @Override
                    public Verdict decide(AgentJob o) {
                        return Verdict.FAIL;
                    }

                    @Override
                    public String takeNeedsReviewReason(AgentJob o) {
                        return "an email tool call may already have been sent";
                    }
                };

        runnerWith(policy).reapOrphans();

        verify(repository)
                .failIfStillRunning(
                        eq(orphan.getId()),
                        eq("ORPHANED_NEEDS_REVIEW"),
                        org.mockito.ArgumentMatchers.contains(
                                "an email tool call may already have been sent"),
                        any(Instant.class));
        verify(repository, never()).requeueIfStillRunning(any(UUID.class), any(Instant.class));
    }

    @Test
    void withNoPolicyBeanTheSweepBehavesExactlyAsItShipped() {
        // The ObjectProvider default. A deployment without this feature's bean
        // must reap byte-for-byte as it always has.
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();
        stale(orphan);
        when(executor.isActiveHere(orphan.getId())).thenReturn(false);
        when(repository.failIfStillRunning(
                        any(UUID.class), anyString(), anyString(), any(Instant.class)))
                .thenReturn(1);

        new AgentJobRunner(repository, executor, properties, schemaIterator, jobLock).reapOrphans();

        verify(repository)
                .failIfStillRunning(
                        eq(orphan.getId()), eq("ORPHANED"), anyString(), any(Instant.class));
        verify(repository, never()).requeueIfStillRunning(any(UUID.class), any(Instant.class));
    }

    private void stale(AgentJob job) {
        when(repository.findByStatusAndHeartbeatAtBefore(
                        eq(AgentJobStatus.RUNNING), any(Instant.class)))
                .thenReturn(List.of(job));
    }

    private ZeroWorkOrphanRecoveryPolicy realPolicy() {
        return new ZeroWorkOrphanRecoveryPolicy(
                properties, toolCallLogRepository, new SimpleMeterRegistry());
    }

    /**
     * The container resolves the policy through {@code ObjectProvider#getIfAvailable(Supplier)}, so
     * the test has to hand the runner one too - stubbing it is what proves the bean actually
     * reaches the sweep rather than the FAIL_LOUDLY default silently winning.
     */
    @SuppressWarnings("unchecked")
    private AgentJobRunner runnerWith(AgentJobOrphanRecoveryPolicy policy) {
        ObjectProvider<AgentJobOrphanRecoveryPolicy> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(
                        ArgumentMatchers.<Supplier<AgentJobOrphanRecoveryPolicy>>any()))
                .thenReturn(policy);
        return new AgentJobRunner(
                repository, executor, properties, schemaIterator, jobLock, provider);
    }
}
