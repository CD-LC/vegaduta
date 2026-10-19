package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The session-id contract, including the one assertion in this whole workstream
 * that is a tripwire on a file we deliberately do not edit.
 */
class AgentJobSessionTest {

    @Test
    void sessionIdRoundTrips() {
        UUID jobId = UUID.randomUUID();
        String sessionId = AgentJobSession.sessionIdFor(jobId);

        assertEquals("job:" + jobId, sessionId);
        assertTrue(AgentJobSession.isJobSession(sessionId));
        assertEquals(Optional.of(jobId), AgentJobSession.jobIdOf(sessionId));
    }

    @Test
    void sessionIdFitsToolCallLogsColumn() {
        // tool_call_log.session_id is VARCHAR(128) NOT NULL. "job:" + a UUID is
        // 40 characters; asserting it here means a future change to the prefix
        // fails the build rather than failing an INSERT in production, mid-job,
        // after the LLM has already been paid for.
        assertEquals(40, AgentJobSession.sessionIdFor(UUID.randomUUID()).length());
        assertTrue(AgentJobSession.sessionIdFor(UUID.randomUUID()).length() <= 128);
    }

    @Test
    void jobIdOfIsTotalAndNeverThrows() {
        // Callers use this to ASK whether an arbitrary session belongs to a job,
        // so every not-a-job-session input must answer "no" rather than throw.
        assertEquals(Optional.empty(), AgentJobSession.jobIdOf(null));
        assertEquals(Optional.empty(), AgentJobSession.jobIdOf(""));
        assertEquals(Optional.empty(), AgentJobSession.jobIdOf("console-session-42"));
        assertEquals(Optional.empty(), AgentJobSession.jobIdOf("job:not-a-uuid"));
        assertEquals(Optional.empty(), AgentJobSession.jobIdOf("prefixjob:" + UUID.randomUUID()));

        assertFalse(AgentJobSession.isJobSession(null));
        assertFalse(AgentJobSession.isJobSession("whatsapp:+9198"));
    }

    /**
     * The drift alarm.
     *
     * <p>{@code GuardedToolCallback} builds this exact string itself when it
     * records an approval request, and this workstream must be able to find
     * those rows for a given job without editing that hot-path file. The literal
     * is asserted rather than re-derived from the production helper, because a
     * test that calls the same method it is checking would agree with any future
     * change - including the one that silently orphans every job's approval
     * requests.
     */
    @Test
    void requestedByLabelMatchesGuardedToolCallbacksLiteralFormat() {
        UUID agentId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID jobId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        assertEquals(
            "agent:11111111-1111-1111-1111-111111111111:session:job:22222222-2222-2222-2222-222222222222",
            AgentJobSession.requestedByLabel(agentId, jobId));
    }

    @Test
    void everyJobsSessionIdIsDerivedFromItsOwnId() {
        // Never client-supplied: two jobs sharing a session would cross-correlate
        // each other's tool logs and (once approval parking lands) each other's
        // blockers.
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        assertEquals(AgentJobSession.sessionIdFor(job.getId()), job.getSessionId());
    }
}
