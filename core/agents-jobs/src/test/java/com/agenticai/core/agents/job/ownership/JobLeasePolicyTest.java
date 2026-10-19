package com.agenticai.core.agents.job.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.job.ownership.JobLeasePolicy.RenewOutcome;
import com.agenticai.core.agents.job.ownership.JobLeasePolicy.Timing;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JobLeasePolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    @Test
    void defaultsAreAcceptedUnchanged() {
        Timing t = JobLeasePolicy.effectiveTiming(60, 15, 15);
        assertThat(t.leaseSeconds()).isEqualTo(60);
        assertThat(t.heartbeatSeconds()).isEqualTo(15);
        assertThat(t.adjusted()).isFalse();
    }

    @Test
    void aLeaseShorterThanThreeHeartbeatsIsRaised() {
        Timing t = JobLeasePolicy.effectiveTiming(20, 15, 15);
        assertThat(t.leaseSeconds()).isEqualTo(45);
        assertThat(t.adjusted()).isTrue();
    }

    @Test
    void aLeaseBelowTheAbsoluteFloorIsRaised() {
        Timing t = JobLeasePolicy.effectiveTiming(5, 2, 1);
        assertThat(t.leaseSeconds()).isEqualTo(JobLeasePolicy.MIN_LEASE_SECONDS);
        assertThat(t.heartbeatSeconds()).isEqualTo(2);
        assertThat(t.adjusted()).isTrue();
    }

    @Test
    void theSlowerLegacyHeartbeatOfNotYetUpgradedReplicasAlsoWidensTheLease() {
        Timing t = JobLeasePolicy.effectiveTiming(60, 5, 30);
        assertThat(t.leaseSeconds()).isEqualTo(90);
    }

    @Test
    void nonPositiveValuesFallBackToDefaults() {
        Timing t = JobLeasePolicy.effectiveTiming(0, -3, 15);
        assertThat(t.leaseSeconds()).isEqualTo(JobLeasePolicy.DEFAULT_LEASE_SECONDS);
        assertThat(t.heartbeatSeconds()).isEqualTo(JobLeasePolicy.DEFAULT_HEARTBEAT_SECONDS);
        assertThat(t.adjusted()).isTrue();
    }

    @Test
    void aFreshHeartbeatIsNotExpired() {
        assertThat(JobLeasePolicy.isExpired(NOW.minusSeconds(10), NOW, 60)).isFalse();
    }

    @Test
    void theLeaseEndsOnlyAfterLeasePlusSkewGrace() {
        // exactly lease + grace old: cutoff is strict "before", so still alive
        Instant atBoundary = NOW.minusSeconds(60 + JobLeasePolicy.CLOCK_SKEW_GRACE_SECONDS);
        assertThat(JobLeasePolicy.isExpired(atBoundary, NOW, 60)).isFalse();
        assertThat(JobLeasePolicy.isExpired(atBoundary.minusSeconds(1), NOW, 60)).isTrue();
    }

    @Test
    void aNullHeartbeatIsNeverExpiredFailSafe() {
        assertThat(JobLeasePolicy.isExpired(null, NOW, 60)).isFalse();
        assertThat(JobLeasePolicy.expiresAt(null, 60)).isNull();
    }

    @Test
    void aReleasedLeaseIsExpiredImmediately() {
        assertThat(JobLeasePolicy.isReleased(JobLeasePolicy.RELEASED_AT)).isTrue();
        assertThat(JobLeasePolicy.isExpired(JobLeasePolicy.RELEASED_AT, NOW, 60)).isTrue();
        assertThat(JobLeasePolicy.isReleased(NOW)).isFalse();
        assertThat(JobLeasePolicy.isReleased(null)).isFalse();
    }

    @Test
    void aReleasedHeartbeatAlwaysSitsBelowTheExpiryCutoffSoTheGuardedUpdateMatches() {
        assertThat(JobLeasePolicy.RELEASED_AT).isBefore(JobLeasePolicy.expiryCutoff(NOW, 60));
    }

    @Test
    void expiresAtIsHeartbeatPlusLease() {
        assertThat(JobLeasePolicy.expiresAt(NOW, 60)).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void onlyARunningRowOwnedBySomebodyElseProvesTheLeaseWasLost() {
        assertThat(JobLeasePolicy.classifyFailedRenewal(AgentJobStatus.RUNNING, "b", "a")).isEqualTo(RenewOutcome.LOST);
        // a worker registered before its claim: QUEUED + no owner must NOT be fenced
        assertThat(JobLeasePolicy.classifyFailedRenewal(AgentJobStatus.QUEUED, null, "a")).isEqualTo(RenewOutcome.UNKNOWN);
        assertThat(JobLeasePolicy.classifyFailedRenewal(AgentJobStatus.AWAITING_APPROVAL, null, "a"))
            .isEqualTo(RenewOutcome.UNKNOWN);
        assertThat(JobLeasePolicy.classifyFailedRenewal(AgentJobStatus.SUCCEEDED, null, "a")).isEqualTo(RenewOutcome.UNKNOWN);
        assertThat(JobLeasePolicy.classifyFailedRenewal(AgentJobStatus.RUNNING, null, "a")).isEqualTo(RenewOutcome.UNKNOWN);
        assertThat(JobLeasePolicy.classifyFailedRenewal(AgentJobStatus.RUNNING, "a", "a")).isEqualTo(RenewOutcome.UNKNOWN);
    }

    @Test
    void onlyTheOwnerOfARunningRowMayWriteItsTerminalState() {
        assertThat(JobLeasePolicy.holdsLease(AgentJobStatus.RUNNING, "a", "a")).isTrue();
        assertThat(JobLeasePolicy.holdsLease(AgentJobStatus.RUNNING, "b", "a")).isFalse();
        assertThat(JobLeasePolicy.holdsLease(AgentJobStatus.QUEUED, null, "a")).isFalse();
        assertThat(JobLeasePolicy.holdsLease(AgentJobStatus.RUNNING, null, "a")).isFalse();
    }
}
