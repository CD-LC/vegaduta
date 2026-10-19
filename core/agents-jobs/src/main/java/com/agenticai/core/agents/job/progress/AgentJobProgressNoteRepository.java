package com.agenticai.core.agents.job.progress;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and appends a job's progress trail.
 *
 * <p>Every ordering here is {@code (createdAt ASC, id ASC)}, never {@code
 * createdAt} alone. The id tiebreak is what makes the order TOTAL, and a total
 * order is what makes the {@code ?after=<instant>} poll cursor correct: with
 * ties left unordered, two notes written in the same microsecond can swap places
 * between two polls, so the live trace stream would either show one twice or
 * skip it entirely. There is no sequence column to order by instead - see
 * V209's closing comment for why (Spring AI dispatches tool calls in parallel,
 * so a writer-computed {@code MAX(seq)+1} would be racy).
 */
public interface AgentJobProgressNoteRepository extends JpaRepository<AgentJobProgressNote, UUID> {

    List<AgentJobProgressNote> findByJobIdOrderByCreatedAtAscIdAsc(UUID jobId);

    /**
     * The live-trace poll. STRICTLY after, so a client that re-sends the
     * timestamp of the last note it received does not get that note again.
     */
    List<AgentJobProgressNote> findByJobIdAndCreatedAtAfterOrderByCreatedAtAscIdAsc(UUID jobId, Instant after);

    /**
     * Backs the per-job note cap ({@code app.agent-jobs.progress.max-notes-per-job}).
     * A job stuck in a loop calling {@code report_progress} must not be able to
     * write unbounded rows into a tenant's schema - the cap is a storage guard,
     * not a rate limit.
     */
    long countByJobId(UUID jobId);

    /**
     * The most recent note, for the dedupe window. Reading the newest ROW rather
     * than trusting {@code agent_job.progress_note} on the worker's loaded copy:
     * that copy is stale by construction (it was loaded before a turn that may
     * have run for minutes) and the note may have been written by a tool call on
     * a different Reactor thread entirely.
     */
    Optional<AgentJobProgressNote> findFirstByJobIdOrderByCreatedAtDescIdDesc(UUID jobId);
}
