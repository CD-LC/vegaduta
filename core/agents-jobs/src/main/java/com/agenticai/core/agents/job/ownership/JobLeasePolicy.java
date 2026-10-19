package com.agenticai.core.agents.job.ownership;

import com.agenticai.core.agents.job.AgentJobStatus;
import java.time.Instant;

/**
 * Pure lease arithmetic for agent-job ownership. No Spring, no I/O, no clock: every method takes its "now".
 *
 * <p><b>The lease lives in existing columns - no migration.</b> A running job's lease is
 * {@code agent_job.heartbeat_at + lease-seconds}, renewed by the owning replica's heartbeat tick (one UPDATE
 * per job per tick) and owned by {@code agent_job.owner_instance}. A graceful release is simply
 * {@code heartbeat_at = }{@link #RELEASED_AT}: the row then looks expired to the new lease sweep AND to the
 * pre-existing orphan sweep of a replica that does not know about leases, which is what makes a mixed
 * old/new rolling deploy safe.
 *
 * <p><b>Fail safe.</b> A null heartbeat is never "expired" here (a RUNNING row always gets one at claim; a
 * null means something unexpected and the conservative answer is to leave the job alone).
 */
public final class JobLeasePolicy {

    public static final int DEFAULT_LEASE_SECONDS = 60;
    public static final int DEFAULT_HEARTBEAT_SECONDS = 15;

    /** A lease shorter than this would expire during an ordinary GC pause or a slow DB round-trip. */
    public static final int MIN_LEASE_SECONDS = 30;

    /** The lease must outlive at least this many heartbeats, so one missed tick never costs a job. */
    public static final int HEARTBEATS_PER_LEASE = 3;

    /**
     * Extra slack added to every expiry comparison because heartbeat_at is written with the OWNER's clock and
     * compared with the SWEEPER's clock (two replicas, two NTP offsets).
     */
    public static final int CLOCK_SKEW_GRACE_SECONDS = 5;

    /** Written to {@code heartbeat_at} by a graceful release: "my lease ends now, adopt me". */
    public static final Instant RELEASED_AT = Instant.EPOCH;

    private JobLeasePolicy() {
    }

    /**
     * The validated lease/heartbeat pair.
     *
     * @param adjusted true when the configured values were unsafe and had to be corrected (logged once at boot)
     */
    public record Timing(int leaseSeconds, int heartbeatSeconds, boolean adjusted) {
    }

    /** What a renewal attempt that matched no row means. */
    public enum RenewOutcome {
        /** The UPDATE matched: this replica still holds the lease. */
        RENEWED,
        /** Another replica owns the RUNNING row: this worker is a zombie and must stop. */
        LOST,
        /** Cannot tell (not claimed yet, parked, finished, row gone, lookup failed): do nothing. */
        UNKNOWN
    }

    /**
     * Corrects an unsafe configuration instead of honouring it: a lease that cannot outlive three heartbeats
     * (of this replica or of a not-yet-upgraded one) would expire live jobs.
     *
     * @param legacyHeartbeatSeconds the pre-existing {@code app.agent-jobs.heartbeat-interval}, which replicas
     *     without ownership still use
     */
    public static Timing effectiveTiming(int leaseSeconds, int heartbeatSeconds, int legacyHeartbeatSeconds) {
        int hb = heartbeatSeconds >= 1 ? heartbeatSeconds : DEFAULT_HEARTBEAT_SECONDS;
        int lease = leaseSeconds >= 1 ? leaseSeconds : DEFAULT_LEASE_SECONDS;
        boolean adjusted = hb != heartbeatSeconds || lease != leaseSeconds;
        int slowest = Math.max(hb, Math.max(1, legacyHeartbeatSeconds));
        int minLease = Math.max(MIN_LEASE_SECONDS, HEARTBEATS_PER_LEASE * slowest);
        if (lease < minLease) {
            lease = minLease;
            adjusted = true;
        }
        return new Timing(lease, hb, adjusted);
    }

    /** When the lease last renewed at {@code heartbeatAt} ends (without skew grace). Null in, null out. */
    public static Instant expiresAt(Instant heartbeatAt, int leaseSeconds) {
        return heartbeatAt == null ? null : heartbeatAt.plusSeconds(leaseSeconds);
    }

    /**
     * Rows with {@code heartbeat_at} strictly before this instant are expired. The same value is used for the
     * sweep's read and for the compare-and-set UPDATE guard, so both agree.
     */
    public static Instant expiryCutoff(Instant now, int leaseSeconds) {
        return now.minusSeconds((long) leaseSeconds + CLOCK_SKEW_GRACE_SECONDS);
    }

    public static boolean isReleased(Instant heartbeatAt) {
        return heartbeatAt != null && !heartbeatAt.isAfter(RELEASED_AT);
    }

    public static boolean isExpired(Instant heartbeatAt, Instant now, int leaseSeconds) {
        if (heartbeatAt == null) {
            return false;
        }
        if (isReleased(heartbeatAt)) {
            return true;
        }
        return heartbeatAt.isBefore(expiryCutoff(now, leaseSeconds));
    }

    /**
     * Classifies a renewal that updated zero rows. Only "RUNNING and owned by somebody else" proves the lease
     * was lost: a QUEUED row may simply not be claimed by this worker yet (the executor registers a job
     * before its claim), and a parked or finished row is handled by other paths.
     */
    public static RenewOutcome classifyFailedRenewal(AgentJobStatus status, String owner, String me) {
        if (status == AgentJobStatus.RUNNING && owner != null && me != null && !owner.equals(me)) {
            return RenewOutcome.LOST;
        }
        return RenewOutcome.UNKNOWN;
    }

    /** Whether this replica may write a job's terminal state: only while it provably still owns the RUNNING row. */
    public static boolean holdsLease(AgentJobStatus status, String owner, String me) {
        return status == AgentJobStatus.RUNNING && owner != null && owner.equals(me);
    }
}
