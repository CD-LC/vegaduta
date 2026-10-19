package com.agenticai.core.agents.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.ownership.JobLeaseStore;
import com.agenticai.core.agents.job.ownership.JobOwnership;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The OUT-OF-BAND sweeps: the wall clock that actually enforces itself, and the queue-TTL expiry.
 *
 * <p>{@link AgentJobRunner#enforceDeadlines} looks redundant next to the {@code block(remaining)}
 * inside {@link AgentJobExecution} and is not - see that method's javadoc for why {@code block}'s
 * timer cannot fire for a PLANNED/CAREFUL/RIGOROUS turn. This test exists partly so that deleting
 * the sweep as duplication turns red instead of silently removing the only working timeout for a
 * large class of jobs.
 *
 * <p>Both sweeps write through GUARDED conditional UPDATEs, which is what makes them safe against a
 * worker finishing normally in the same instant: whichever write lands first wins and the other
 * sees rowcount 0. The assertions below are on those guarded calls specifically, never on {@code
 * save}.
 */
class AgentJobDeadlineSweepTest {

    private AgentJobRepository repository;
    private AgentJobExecutor executor;
    private AgentJobProperties properties;
    private TenantSchemaIterator schemaIterator;
    private PgAdvisoryJobLock jobLock;
    private AgentJobRunner runner;

    @BeforeEach
    void setUp() {
        repository = mock(AgentJobRepository.class);
        executor = mock(AgentJobExecutor.class);
        properties = AgentJobTestSupport.properties();

        schemaIterator = mock(TenantSchemaIterator.class);
        doAnswer(
                        invocation -> {
                            invocation.getArgument(0, Consumer.class).accept("tenant_acme");
                            return null;
                        })
                .when(schemaIterator)
                .forEachActiveTenantSchema(any());
        doAnswer(
                        invocation -> {
                            invocation
                                    .getArgument(1, java.util.function.Predicate.class)
                                    .test("tenant_acme");
                            return null;
                        })
                .when(schemaIterator)
                .forEachActiveTenantSchemaSkippingIdle(anyString(), any());

        jobLock = mock(PgAdvisoryJobLock.class);
        when(jobLock.tryRunExclusive(anyString(), any(Runnable.class)))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(1, Runnable.class).run();
                            return true;
                        });

        runner = new AgentJobRunner(repository, executor, properties, schemaIterator, jobLock);
    }

    /** The same runner, with its clock pinned - see the queue-TTL test for why. */
    private AgentJobRunner runnerAt(Instant now) {
        return new AgentJobRunner(
                repository,
                executor,
                properties,
                schemaIterator,
                jobLock,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void aRunningJobPastItsDeadlineIsFailedWithATimeoutReason() {
        AgentJob overdue = AgentJobTestSupport.queuedJob(60, 15);
        overdue.markRunning("host-a:1", Instant.now().minusSeconds(300));
        when(repository.findByStatusAndDeadlineAtBefore(
                        eq(AgentJobStatus.RUNNING), any(Instant.class)))
                .thenReturn(List.of(overdue));
        when(repository.failIfStillRunning(
                        eq(overdue.getId()), anyString(), anyString(), any(Instant.class)))
                .thenReturn(1);

        runner.enforceDeadlines();

        verify(repository)
                .failIfStillRunning(
                        eq(overdue.getId()), eq("TIMEOUT"), anyString(), any(Instant.class));
        // Durable verdict first, best-effort local interrupt second - and only
        // when the guarded UPDATE actually won.
        verify(executor).stopLocally(overdue.getId());
    }

    @Test
    void losingTheGuardedUpdateMeansTheWorkerAlreadyFinishedAndNothingIsInterrupted() {
        AgentJob overdue = AgentJobTestSupport.queuedJob(60, 15);
        overdue.markRunning("host-a:1", Instant.now().minusSeconds(300));
        when(repository.findByStatusAndDeadlineAtBefore(
                        eq(AgentJobStatus.RUNNING), any(Instant.class)))
                .thenReturn(List.of(overdue));
        when(repository.failIfStillRunning(
                        any(UUID.class), anyString(), anyString(), any(Instant.class)))
                .thenReturn(0);

        runner.enforceDeadlines();

        verify(executor, never()).stopLocally(any(UUID.class));
    }

    @Test
    void oneBadRowDoesNotStopTheRestOfTheSweep() {
        AgentJob first = AgentJobTestSupport.queuedJob(60, 15);
        AgentJob second = AgentJobTestSupport.queuedJob(60, 15);
        first.markRunning("host-a:1", Instant.now().minusSeconds(300));
        second.markRunning("host-a:1", Instant.now().minusSeconds(300));
        when(repository.findByStatusAndDeadlineAtBefore(
                        eq(AgentJobStatus.RUNNING), any(Instant.class)))
                .thenReturn(List.of(first, second));
        when(repository.failIfStillRunning(
                        eq(first.getId()), anyString(), anyString(), any(Instant.class)))
                .thenThrow(new IllegalStateException("connection reset"));
        when(repository.failIfStillRunning(
                        eq(second.getId()), anyString(), anyString(), any(Instant.class)))
                .thenReturn(1);

        runner.enforceDeadlines();

        // The per-item try/catch is the difference between one transient DB blip
        // and every other overdue job in the tenant staying RUNNING forever.
        verify(repository, times(1))
                .failIfStillRunning(
                        eq(second.getId()), eq("TIMEOUT"), anyString(), any(Instant.class));
    }

    @Test
    void aJobQueuedLongerThanTheTtlIsExpiredRatherThanRun() {
        // Its requester's roles were frozen at submit time; running an hour-old
        // snapshot of someone's authority means acting on permissions nobody has
        // re-checked since.
        AgentJob stale = AgentJobTestSupport.queuedJob(60, 15);
        when(repository.findTop50ByStatusOrderByCreatedAtAsc(AgentJobStatus.QUEUED))
                .thenReturn(List.of(stale));
        when(repository.failIfStillQueued(
                        eq(stale.getId()), anyString(), anyString(), any(Instant.class)))
                .thenReturn(1);
        // The staleness comparison is strict, so a zero TTL against the real
        // clock can stamp and sweep on the same tick (Windows clocks are coarse
        // enough for this to actually happen). Pinning the sweep's clock one
        // second past the TTL makes the row stale by construction, no sleeping.
        runner = runnerAt(stale.getCreatedAt().plus(properties.getQueueTtl()).plusSeconds(1));

        runner.dispatchQueued();

        verify(repository)
                .failIfStillQueued(
                        eq(stale.getId()),
                        eq("QUEUE_TTL_EXPIRED"),
                        anyString(),
                        any(Instant.class));
        verify(executor, never()).tryDispatch(any(UUID.class), anyString());
    }

    @Test
    void aFreshQueuedJobIsDispatchedRatherThanExpired() {
        AgentJob fresh = AgentJobTestSupport.queuedJob(60, 15);
        when(repository.findTop50ByStatusOrderByCreatedAtAsc(AgentJobStatus.QUEUED))
                .thenReturn(List.of(fresh));
        when(executor.isSaturated()).thenReturn(false);

        runner.dispatchQueued();

        verify(executor).tryDispatch(fresh.getId(), fresh.getTenantId());
        verify(repository, never())
                .failIfStillQueued(any(UUID.class), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void aRunningJobWhoseHeartbeatStoppedElsewhereIsReapedButOneRunningHereIsNot() {
        AgentJob abandoned = AgentJobTestSupport.queuedJob(60, 15);
        AgentJob liveHere = AgentJobTestSupport.queuedJob(60, 15);
        abandoned.markRunning("dead-replica:9", Instant.now().minusSeconds(600));
        liveHere.markRunning(AgentJobExecutor.instanceId(), Instant.now().minusSeconds(600));
        when(repository.findByStatusAndHeartbeatAtBefore(
                        eq(AgentJobStatus.RUNNING), any(Instant.class)))
                .thenReturn(List.of(abandoned, liveHere));
        when(executor.isActiveHere(abandoned.getId())).thenReturn(false);
        when(executor.isActiveHere(liveHere.getId())).thenReturn(true);
        when(repository.failIfStillRunning(
                        any(UUID.class), anyString(), anyString(), any(Instant.class)))
                .thenReturn(1);

        runner.reapOrphans();

        verify(repository)
                .failIfStillRunning(
                        eq(abandoned.getId()), eq("ORPHANED"), anyString(), any(Instant.class));
        // A slow tick on one replica must never reap another replica's - or its
        // own - genuinely live work.
        verify(repository, never())
                .failIfStillRunning(
                        eq(liveHere.getId()), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void withOwnershipEnabledTheLegacyOrphanSweepStandsDownForTheLeaseSweep() {
        properties.getOwnership().setEnabled(true);
        runner.setOwnership(new JobOwnership(properties, mock(JobLeaseStore.class)));

        runner.reapOrphans();

        verify(jobLock, never()).tryRunExclusive(anyString(), any(Runnable.class));
        verify(repository, never()).findByStatusAndHeartbeatAtBefore(any(), any());
        verify(repository, never())
                .failIfStillRunning(any(UUID.class), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void withOwnershipPresentButDisabledTheLegacyOrphanSweepRunsExactlyAsBefore() {
        // enabled defaults to false
        runner.setOwnership(new JobOwnership(properties, mock(JobLeaseStore.class)));
        AgentJob abandoned = AgentJobTestSupport.queuedJob(60, 15);
        abandoned.markRunning("dead-replica:9", Instant.now().minusSeconds(600));
        when(repository.findByStatusAndHeartbeatAtBefore(
                        eq(AgentJobStatus.RUNNING), any(Instant.class)))
                .thenReturn(List.of(abandoned));
        when(repository.failIfStillRunning(
                        any(UUID.class), anyString(), anyString(), any(Instant.class)))
                .thenReturn(1);

        runner.reapOrphans();

        verify(repository)
                .failIfStillRunning(
                        eq(abandoned.getId()), eq("ORPHANED"), anyString(), any(Instant.class));
    }
}
