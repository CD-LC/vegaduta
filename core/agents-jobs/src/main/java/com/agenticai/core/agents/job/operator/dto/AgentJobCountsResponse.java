package com.agenticai.core.agents.job.operator.dto;

import com.agenticai.core.agents.job.AgentJobStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The operator dashboard's header line: how much work is in flight right now,
 * and how much has finished badly lately.
 *
 * <p>This is the counterpart to {@code GET /api/agent-jobs}'s page of rows. That
 * endpoint answers "show me the jobs"; a page of fifty rows cannot answer "are
 * there four hundred more behind them", which is the question that decides
 * whether an operator needs to do anything. Deliberately NOT folded into the
 * list response as a {@code total}: the list is filtered per row against the
 * caller's agent visibility AFTER paging, so a total attached to it would be a
 * pre-filter number sitting next to post-filter rows - a discrepancy that reads
 * as a bug every single time.
 *
 * <p><b>The live counts are all-time and the terminal counts are windowed, and
 * the field names say so rather than a javadoc hoping to be read.</b> {@link
 * #queued}, {@link #running} and {@link #awaitingApproval} are complete
 * populations - they drain on their own, so a partial count of them would be
 * actively misleading. {@link #succeededInWindow} and its siblings cover only
 * jobs that FINISHED inside {@link #window}, because terminal rows accumulate
 * forever and "failed ever" is not a signal.
 *
 * @param queued             enqueued, not yet claimed by a worker - the backlog
 * @param running            claimed and executing; the only status that occupies
 *                           one of the bounded worker threads
 * @param awaitingApproval   parked on a human approval gate. Counted separately
 *                           and never folded into "finished": {@link
 *                           AgentJobStatus} records that the workflow engine
 *                           originally shipped its approval state as effectively
 *                           terminal and had to be corrected, and a dashboard
 *                           that hides parked jobs is precisely how that defect
 *                           reaches an operator - work stops and every number on
 *                           the screen looks healthy
 * @param live               {@code queued + running + awaitingApproval}. Served
 *                           pre-summed rather than left to the client because
 *                           three clients would otherwise each decide for
 *                           themselves whether a parked job counts, and one of
 *                           them would decide wrong
 * @param succeededInWindow  finished successfully inside {@code window}
 * @param failedInWindow     finished with an error inside {@code window}
 * @param cancelledInWindow  cancelled by an operator, or by the deadline sweep,
 *                           inside {@code window}
 * @param window             the window ACTUALLY applied after clamping, echoed
 *                           back so a client that asked for a year can see it
 *                           was given thirty days instead of silently
 *                           misreading the numbers
 * @param windowStart        {@code asOf - window}, precomputed so a client
 *                           labelling the panel does not re-derive it and drift
 * @param asOf               when the roll-up was taken. These counts are a
 *                           snapshot of a table that changes under them; a
 *                           dashboard that polls needs to be able to say how old
 *                           its numbers are
 * @param agentId            the single agent these counts cover, or {@code null}
 *                           when they span every agent in scope
 * @param tenantWide         {@code true} only when the counts cover the whole
 *                           tenant. {@code false} means they were narrowed to
 *                           the agents this caller can see, so a non-admin
 *                           reading "0 running" knows it means "none of mine",
 *                           not "none at all" - an honest scope label instead of
 *                           a number that quietly means something different
 *                           depending on who asked
 */
public record AgentJobCountsResponse(
    long queued,
    long running,
    long awaitingApproval,
    long live,
    long succeededInWindow,
    long failedInWindow,
    long cancelledInWindow,
    Duration window,
    Instant windowStart,
    Instant asOf,
    UUID agentId,
    boolean tenantWide
) {

    /**
     * Builds the response from a sparse status-&gt;count map.
     *
     * <p>Sparse on purpose: the roll-up query emits no group for a status with
     * no rows, and defaulting the absent ones to zero HERE means every caller
     * gets a complete, six-field answer. A dashboard must render "0 running"
     * rather than omitting the tile - a missing number and a zero look identical
     * to a glance and mean opposite things.
     */
    public static AgentJobCountsResponse of(
        Map<AgentJobStatus, Long> counts,
        Duration window,
        Instant asOf,
        UUID agentId,
        boolean tenantWide
    ) {
        long queued = at(counts, AgentJobStatus.QUEUED);
        long running = at(counts, AgentJobStatus.RUNNING);
        long awaitingApproval = at(counts, AgentJobStatus.AWAITING_APPROVAL);
        return new AgentJobCountsResponse(
            queued,
            running,
            awaitingApproval,
            queued + running + awaitingApproval,
            at(counts, AgentJobStatus.SUCCEEDED),
            at(counts, AgentJobStatus.FAILED),
            at(counts, AgentJobStatus.CANCELLED),
            window,
            asOf.minus(window),
            asOf,
            agentId,
            tenantWide
        );
    }

    private static long at(Map<AgentJobStatus, Long> counts, AgentJobStatus status) {
        Long value = counts.get(status);
        return value == null ? 0L : value;
    }
}
