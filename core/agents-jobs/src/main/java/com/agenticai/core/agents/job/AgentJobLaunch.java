package com.agenticai.core.agents.job;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Everything needed to enqueue a job from a NON-HTTP origin - a fired schedule,
 * later an event trigger. The HTTP path goes through {@code
 * AgentJobService#submit} instead, which additionally performs the access check
 * that a request-scoped caller needs and this one deliberately does not repeat.
 *
 * <p>{@code requestedBy}/{@code requestedByRoles}/{@code subjectKey} are
 * mandatory rather than defaulted to some system principal, and that is the
 * whole point of the record: a scheduled job must run as the human who
 * CONFIGURED the schedule, with that human's authority and against that human's
 * spend ceiling. A "system" identity here would create an agent that can act
 * with more authority than any person, and a budget nobody owns.
 *
 * @param occurrenceAt        the scheduled instant this firing represents (not
 *     the instant it actually fired). Paired with {@code scheduleId} it is what
 *     V208's "exactly once per occurrence" unique index is enforced on, which
 *     is why it must be the SLOT, not {@code now}.
 * @param timeoutSecondsOrNull {@code null} to take {@code
 *     app.agent-jobs.default-timeout}.
 * @param triggerId    the {@code agent_event_trigger} that fired, or {@code
 *     null} for every other origin. Recorded on the job in the same INSERT, so
 *     "which trigger produced this?" is answerable from the row alone.
 * @param triggerDepth reserved, and recorded as {@code 0} today. There is no
 *     reliable lineage signal available - the causing job's identity does not
 *     survive to the thread that publishes a follow-on event - so this must not
 *     be presented as working loop detection. Loop protection is the per-trigger
 *     rolling-window firing cap, not this number.
 */
public record AgentJobLaunch(
    UUID agentId,
    String goal,
    AgentJobOrigin origin,
    String originRef,
    UUID scheduleId,
    Instant occurrenceAt,
    String requestedBy,
    Set<String> requestedByRoles,
    String subjectKey,
    Integer timeoutSecondsOrNull,
    UUID triggerId,
    int triggerDepth
) {

    /**
     * The pre-trigger shape, for every launch that is not event-driven.
     *
     * <p>Kept as a real constructor rather than making the two new components
     * optional at the call site, because the schedule path - the only existing
     * caller, in a package this workstream does not own - would otherwise need
     * an edit purely to pass {@code (null, 0)}. A record's canonical constructor
     * cannot be defaulted, so this is the mechanism.
     */
    public AgentJobLaunch(
        UUID agentId,
        String goal,
        AgentJobOrigin origin,
        String originRef,
        UUID scheduleId,
        Instant occurrenceAt,
        String requestedBy,
        Set<String> requestedByRoles,
        String subjectKey,
        Integer timeoutSecondsOrNull
    ) {
        this(
            agentId, goal, origin, originRef, scheduleId, occurrenceAt,
            requestedBy, requestedByRoles, subjectKey, timeoutSecondsOrNull, null, 0
        );
    }
}
