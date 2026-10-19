package com.agenticai.core.agents.job.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link AgentScheduleRun}. */
public interface AgentScheduleRunRepository extends JpaRepository<AgentScheduleRun, UUID> {

    /** Newest first; the page bounds both the read API and the prune. */
    List<AgentScheduleRun> findByScheduleIdOrderByStartedAtDesc(UUID scheduleId, Pageable pageable);

    /** A workflow run of this schedule still marked RUNNING and started after {@code since}. */
    Optional<AgentScheduleRun> findFirstByScheduleIdAndStatusAndStartedAtAfterOrderByStartedAtDesc(
        UUID scheduleId, String status, Instant since);

    /**
     * The sentinel sweep's read: firings of this schedule that produced an agent
     * job and have not been verdict-evaluated yet. Oldest first so a backlog
     * drains in firing order. Bounded by the run-history cap (50 per schedule),
     * never an unbounded scan.
     */
    List<AgentScheduleRun> findByScheduleIdAndJobIdIsNotNullAndSentinelOutcomeIsNullOrderByStartedAtAsc(
        UUID scheduleId);

    /**
     * The most recent ALREADY-evaluated firing of this schedule - the sentinel
     * sweep's failure-streak check ("did the last judged run also die?") so a
     * broken watch brief notifies once per outage, not once per firing.
     */
    Optional<AgentScheduleRun> findFirstByScheduleIdAndSentinelOutcomeIsNotNullOrderByStartedAtDesc(
        UUID scheduleId);

    /**
     * The ambient digest's "while you were away" read: judged sentinel firings
     * since {@code since}, newest first. Bounded - a sentinel tenant with a
     * busy day still gets a page, not a scan.
     */
    List<AgentScheduleRun> findTop50BySentinelOutcomeIsNotNullAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
        Instant since);
}
