package com.agenticai.core.agents.job.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for {@link AgentSchedule}.
 *
 * <p>The one thing here that is not ordinary CRUD is {@link #claimOccurrence},
 * and it carries the whole multi-replica correctness story for the tick - see
 * its own javadoc.
 */
public interface AgentScheduleRepository extends JpaRepository<AgentSchedule, UUID> {

    /** One schedule per agent (V208's unique constraint), so this is a single row, not a list. */
    Optional<AgentSchedule> findByAgentId(UUID agentId);

    /**
     * The tick's read: enabled schedules whose slot has already passed.
     *
     * <p>Capped at 200 rows so a tenant that somehow accumulated thousands
     * cannot make one tick allocate an unbounded list on the shared 8-thread
     * scheduling pool; the next tick continues where this one stopped. In
     * practice {@code app.agent-jobs.schedule.max-per-tenant} (25) keeps this far
     * below the cap.
     */
    List<AgentSchedule> findTop200ByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(Instant now);

    /** Enforces {@code app.agent-jobs.schedule.max-per-tenant} on the write path. */
    long countByEnabledTrue();

    /**
     * The sentinel sweep's per-tenant read: every schedule in sentinel mode,
     * enabled or not (a disabled sentinel has no new runs, but its last firing
     * may still be unjudged). Small by construction - {@code
     * app.agent-jobs.schedule.max-per-tenant} bounds enabled schedules.
     */
    List<AgentSchedule> findBySentinelEnabledTrue();

    /** Ambient digest: "how many watches are configured" for the tenant. */
    long countBySentinelEnabledTrue();

    /**
     * Claims one occurrence by advancing {@code next_run_at} from the value this
     * replica OBSERVED to the next slot - a compare-and-set, the same idiom
     * {@code AgentJobRepository#claim} and {@code
     * WorkflowRunRepository#compareAndSetStatus} use.
     *
     * <p>Rowcount 1 means this replica owns this firing. Rowcount 0 means either
     * another replica claimed it microseconds earlier or an admin disabled the
     * schedule in between; both are handled by returning SILENTLY. Neither is a
     * skip and neither may touch the skip counters - counting a lost claim race
     * as a skip would make a healthy two-replica deployment look permanently
     * broken on the schedule screen.
     *
     * <p><b>The advance happens BEFORE the overlap decision, deliberately.</b>
     * That ordering is what makes a blocked firing a SKIP (this slot is lost, the
     * next one is already scheduled) instead of a retry that fires the instant
     * the blocking job finishes and then again immediately for every slot that
     * passed meanwhile.
     *
     * @param next computed from NOW rather than from the missed slot - the same
     *     no-backfill rule as {@code WorkflowService#advanceSchedule}, so six
     *     hours of downtime produces ONE firing on boot, not seventy-two.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentSchedule s set s.nextRunAt = :next, s.updatedAt = :now "
        + "where s.id = :id and s.enabled = true and s.nextRunAt = :observed")
    int claimOccurrence(
        @Param("id") UUID id,
        @Param("next") Instant next,
        @Param("now") Instant now,
        @Param("observed") Instant observed
    );

    /**
     * Disables a schedule whose stored cron no longer parses.
     *
     * <p>A targeted UPDATE rather than a load-mutate-save because the tick may be
     * holding a stale copy, and because this is the one write that must land even
     * when everything else about the row is suspect: the alternative is a tick
     * that logs the same parse failure every sixty seconds forever. Same
     * behaviour, same reasoning, as {@code WorkflowService#advanceSchedule}'s
     * disable-on-unparseable-cron branch.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentSchedule s set s.enabled = false, s.nextRunAt = null, s.updatedAt = :now where s.id = :id")
    int disableUnparseable(@Param("id") UUID id, @Param("now") Instant now);
}
