package com.agenticai.core.agents.job;

import java.util.Optional;
import java.util.UUID;

/**
 * The narrow seam the SCHEDULES PR (and later the event-trigger PR) compiles
 * against, so neither of them needs to depend on the full {@link
 * AgentJobService} surface - and so neither can accidentally reach for {@code
 * submit}, which performs an HTTP-caller access check that makes no sense on a
 * scheduler thread.
 *
 * <p><b>{@link #enqueue} MUST let {@code
 * org.springframework.dao.DataIntegrityViolationException} propagate.</b> That
 * is not an oversight to be tidied up with a try/catch: V208's two unique
 * indexes (at most one live job per schedule; exactly one job per occurrence)
 * are the GUARD OF RECORD for scheduled firing, chosen precisely because {@code
 * PgAdvisoryJobLock} is documented fail-open and therefore can never be a
 * correctness guarantee. The scheduler's backstop depends on SEEING that
 * violation and translating it into a recorded skip; swallowing it here would
 * turn "the database correctly prevented a double-charge" into "the job
 * silently vanished".
 */
public interface AgentJobLauncher {

    /**
     * Writes the durable QUEUED row and best-effort hands it to a local worker.
     *
     * @return the new job's id.
     */
    UUID enqueue(AgentJobLaunch launch);

    /**
     * The overlap guard's read: the most recent NON-TERMINAL job for this
     * schedule, if any. Live means {@link AgentJobStatus#live()} - QUEUED counts
     * (a backed-up pool must not accumulate a queue of identical goals) and
     * AWAITING_APPROVAL counts (it is not terminal).
     */
    Optional<AgentJobView> findLiveForSchedule(UUID scheduleId);
}
