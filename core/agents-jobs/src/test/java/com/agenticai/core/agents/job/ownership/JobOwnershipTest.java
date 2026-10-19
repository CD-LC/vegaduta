package com.agenticai.core.agents.job.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobExecutor;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.job.ownership.JobLeasePolicy.RenewOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobOwnershipTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    private static AgentJobProperties enabled() {
        AgentJobProperties p = new AgentJobProperties();
        p.getOwnership().setEnabled(true);
        return p;
    }

    private static AgentJob runningJob(String owner) {
        AgentJob job = AgentJob.queued(UUID.randomUUID(), "t", UUID.randomUUID(), "goal", "alice", "alice",
            Set.of("ROLE_user"), AgentJobOrigin.API, null, 60, 1, 15, null);
        job.markRunning(owner, NOW);
        return job;
    }

    @Test
    void ownershipIsOffByDefaultAndTheOwnerIdIsTheLegacyHostPid() {
        AgentJobProperties p = new AgentJobProperties();
        JobOwnership o = new JobOwnership(p, mock(JobLeaseStore.class));
        assertThat(o.isEnabled()).isFalse();
        assertThat(o.ownerId()).isEqualTo(AgentJobExecutor.instanceId());
    }

    @Test
    void whenEnabledTheOwnerIdCarriesAPerProcessSuffix() {
        JobOwnership o = new JobOwnership(enabled(), mock(JobLeaseStore.class));
        assertThat(o.ownerId()).isEqualTo(JobReplicaId.current());
        assertThat(o.ownerId()).isNotEqualTo(AgentJobExecutor.instanceId());
    }

    @Test
    void aSuccessfulRenewalIsReported() {
        JobLeaseStore store = mock(JobLeaseStore.class);
        UUID id = UUID.randomUUID();
        JobOwnership o = new JobOwnership(enabled(), store);
        when(store.renew(id, o.ownerId(), NOW)).thenReturn(true);
        assertThat(o.renew(id, NOW)).isEqualTo(RenewOutcome.RENEWED);
        verify(store, never()).view(any());
    }

    @Test
    void aMissedRenewalOnARowRunningUnderAnotherOwnerMeansTheLeaseWasLost() {
        JobLeaseStore store = mock(JobLeaseStore.class);
        UUID id = UUID.randomUUID();
        JobOwnership o = new JobOwnership(enabled(), store);
        when(store.renew(id, o.ownerId(), NOW)).thenReturn(false);
        when(store.view(id)).thenReturn(Optional.of(new JobLeaseStore.LeaseView(AgentJobStatus.RUNNING, "other:1:x")));
        assertThat(o.renew(id, NOW)).isEqualTo(RenewOutcome.LOST);
    }

    @Test
    void aMissedRenewalOnAQueuedRowIsNotAnAccusation() {
        JobLeaseStore store = mock(JobLeaseStore.class);
        UUID id = UUID.randomUUID();
        JobOwnership o = new JobOwnership(enabled(), store);
        when(store.renew(id, o.ownerId(), NOW)).thenReturn(false);
        when(store.view(id)).thenReturn(Optional.of(new JobLeaseStore.LeaseView(AgentJobStatus.QUEUED, null)));
        assertThat(o.renew(id, NOW)).isEqualTo(RenewOutcome.UNKNOWN);
    }

    @Test
    void aGoneRowOrADatabaseErrorNeverFencesAJob() {
        JobLeaseStore store = mock(JobLeaseStore.class);
        UUID id = UUID.randomUUID();
        JobOwnership o = new JobOwnership(enabled(), store);
        when(store.renew(id, o.ownerId(), NOW)).thenReturn(false);
        when(store.view(id)).thenReturn(Optional.empty());
        assertThat(o.renew(id, NOW)).isEqualTo(RenewOutcome.UNKNOWN);

        UUID other = UUID.randomUUID();
        when(store.renew(other, o.ownerId(), NOW)).thenThrow(new IllegalStateException("db down"));
        assertThat(o.renew(other, NOW)).isEqualTo(RenewOutcome.UNKNOWN);
    }

    @Test
    void terminalWritesAreAlwaysAllowedWhenOwnershipIsOff() {
        JobOwnership o = new JobOwnership(new AgentJobProperties(), mock(JobLeaseStore.class));
        assertThat(o.mayWriteTerminal(runningJob("somebody-else"))).isTrue();
    }

    @Test
    void terminalWritesRequireOwningTheRunningRowWhenOwnershipIsOn() {
        JobOwnership o = new JobOwnership(enabled(), mock(JobLeaseStore.class));
        assertThat(o.mayWriteTerminal(runningJob(o.ownerId()))).isTrue();
        assertThat(o.mayWriteTerminal(runningJob("somebody-else"))).isFalse();
    }

    @Test
    void shutdownBeginsExactlyOnce() {
        JobOwnership o = new JobOwnership(enabled(), mock(JobLeaseStore.class));
        assertThat(o.isShuttingDown()).isFalse();
        assertThat(o.beginShutdown()).isTrue();
        assertThat(o.beginShutdown()).isFalse();
        assertThat(o.isShuttingDown()).isTrue();
    }

    @Test
    void defaultsMatchTheDocumentedConfiguration() {
        AgentJobProperties.Ownership d = new AgentJobProperties().getOwnership();
        assertThat(d.isEnabled()).isFalse();
        assertThat(d.getLeaseSeconds()).isEqualTo(60);
        assertThat(d.getHeartbeatSeconds()).isEqualTo(15);
        assertThat(d.getResumeMaxAttempts()).isEqualTo(3);
        assertThat(d.getSweepInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(d.effectiveSweepBatch()).isEqualTo(50);
    }

    @Test
    void theHeartbeatIntervalIsUntouchedWhileOwnershipIsOff() {
        AgentJobProperties p = new AgentJobProperties();
        p.setHeartbeatInterval(Duration.ofSeconds(20));
        p.getOwnership().setHeartbeatSeconds(5);
        assertThat(p.effectiveHeartbeatInterval()).isEqualTo(Duration.ofSeconds(20));
        assertThat(p.staleHeartbeatAfter()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void theOwnershipHeartbeatAppliesWhenEnabledAndTheLeaseIsValidated() {
        AgentJobProperties p = new AgentJobProperties();
        p.getOwnership().setEnabled(true);
        p.getOwnership().setHeartbeatSeconds(5);
        p.getOwnership().setLeaseSeconds(10);
        assertThat(p.effectiveHeartbeatInterval()).isEqualTo(Duration.ofSeconds(5));
        JobLeasePolicy.Timing t = p.getOwnership().effectiveTiming(p.getHeartbeatInterval());
        assertThat(t.leaseSeconds()).isGreaterThanOrEqualTo(45); // 3 x the slower legacy 15s heartbeat
        assertThat(t.adjusted()).isTrue();
    }
}
