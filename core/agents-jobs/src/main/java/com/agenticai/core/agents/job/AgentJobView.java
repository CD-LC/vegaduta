package com.agenticai.core.agents.job;

import java.time.Instant;
import java.util.UUID;

/**
 * The minimal projection of a job the schedule overlap guard needs: is there a
 * live job for this schedule, what state is it in, and how OLD is it.
 *
 * <p>Deliberately not the whole {@link AgentJob} - the guard's decision function
 * is specified as PURE (no Spring, no database), and handing it a JPA entity
 * would both make that impossible to hold to and tempt a future edit into
 * lazily loading something inside a scheduler tick.
 *
 * <p>{@code startedAt} is here alongside {@code createdAt} because the
 * "schedule blocked for hours" alert measures how long the BLOCKING job has been
 * non-terminal - the number that turns a support ticket into a diagnosis.
 */
public record AgentJobView(
    UUID id,
    AgentJobStatus status,
    Instant createdAt,
    Instant startedAt
) {

    public static AgentJobView of(AgentJob job) {
        return new AgentJobView(job.getId(), job.getStatus(), job.getCreatedAt(), job.getStartedAt());
    }
}
