package com.agenticai.core.agents.job.operator;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read-only aggregate access for the operator dashboard.
 *
 * <p><b>Why a second repository over {@link AgentJob} instead of two more
 * methods on {@code AgentJobRepository}.</b> That interface is the job
 * primitive's own persistence contract - claim, cancel, heartbeat, the guarded
 * status moves - and it is owned by the foundation PR. Aggregates for a
 * dashboard are a different concern with a different lifetime, and a second
 * Spring Data proxy over the same entity costs nothing at runtime. It also
 * keeps a concurrently-developed hot file closed.
 *
 * <p>Extends the bare {@link Repository} marker, NOT {@code JpaRepository}: this
 * interface must never grow a {@code save} or {@code delete} path. Every write
 * to {@code agent_job} goes through the foundation's guarded statements, and a
 * second bean quietly exposing {@code save(entity)} on the same table is exactly
 * the last-writer-wins window the job design is built to avoid (a stale full-row
 * save is how a concurrently-set {@code cancel_requested} gets clobbered).
 *
 * <p>Both queries take the LIVE statuses as a parameter rather than hard-coding
 * them, so {@link AgentJobStatus#live()} stays the single definition of "not
 * finished yet" - the same reason the schedule overlap guard reads it instead of
 * re-listing the three states at its own call site.
 */
public interface AgentJobOperatorRepository extends Repository<AgentJob, UUID> {

    /**
     * Tenant-wide roll-up, one row per status that has at least one match.
     *
     * <p><b>The window applies to terminal rows only, and that asymmetry is the
     * point.</b> Live rows are counted regardless of age because "queued" is a
     * bounded, self-draining population and an operator asking "what is running"
     * means all of it - a job parked on an approval gate for three days is still
     * the answer to that question. Terminal rows are the opposite: they
     * accumulate forever, so an all-time {@code count(FAILED)} would both grow
     * into an expensive scan and answer a question nobody asked ("how many jobs
     * have ever failed" is not "is something failing right now").
     *
     * <p>Terminal rows are windowed on {@code finished_at}, never {@code
     * created_at}: a job submitted two days ago that failed a minute ago belongs
     * in the last hour's failures. A terminal row with a null {@code
     * finished_at} - only reachable via a hand-run SQL fix, since all three
     * terminal transitions stamp it - falls out of the window rather than
     * distorting it.
     *
     * <p>Statuses with no matching rows are simply absent from the result; the
     * caller defaults them to zero rather than this query trying to synthesize
     * empty groups.
     */
    @Query("select new com.agenticai.core.agents.job.operator.AgentJobStatusCount(j.status, count(j)) "
        + "from AgentJob j "
        + "where j.status in :liveStatuses or j.finishedAt >= :since "
        + "group by j.status")
    List<AgentJobStatusCount> rollUpTenantWide(
        @Param("liveStatuses") Collection<AgentJobStatus> liveStatuses,
        @Param("since") Instant since
    );

    /**
     * The same roll-up narrowed to an explicit set of agent ids - how a caller
     * who is not a tenant-admin gets counts of exactly the agents they can
     * already see in a list, and how the {@code agentId} query parameter is
     * served.
     *
     * <p>An explicit id set rather than a nullable-parameter {@code (:agentIds
     * is null or ...)} trick: this repo has no precedent for that idiom, and two
     * plainly-readable queries beat one query whose plan depends on a parameter
     * being null. The caller short-circuits an EMPTY set before reaching here -
     * an empty {@code IN} list is a question with a known answer, not a query
     * worth issuing.
     */
    @Query("select new com.agenticai.core.agents.job.operator.AgentJobStatusCount(j.status, count(j)) "
        + "from AgentJob j "
        + "where j.agentId in :agentIds and (j.status in :liveStatuses or j.finishedAt >= :since) "
        + "group by j.status")
    List<AgentJobStatusCount> rollUpForAgents(
        @Param("agentIds") Collection<UUID> agentIds,
        @Param("liveStatuses") Collection<AgentJobStatus> liveStatuses,
        @Param("since") Instant since
    );
}
