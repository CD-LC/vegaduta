package com.agenticai.core.agents.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Shared fixtures for the agent-job unit tests.
 *
 * <p>Everything here is a plain Mockito mock with no Spring context and no
 * database - the repo's standing rule for {@code core/agents} tests, because a
 * full {@code mvn test} in {@code core/app} boots a real Postgres and these must
 * stay runnable in seconds.
 *
 * <p><b>The one non-obvious piece is {@link #repositoryFor}.</b> {@link
 * AgentJobRepository#claim} is a conditional UPDATE issued by the database, so
 * in production the worker's next read of the row comes back RUNNING. A naive
 * mock returns a rowcount without changing anything, which leaves the entity
 * QUEUED and makes every later {@code succeed()}/{@code fail()} throw {@link
 * AgentJobIllegalTransitionException} for a reason that has nothing to do with
 * the behaviour under test. The stub below therefore mirrors the UPDATE onto the
 * in-memory entity, exactly as the real claim does to the row.
 */
final class AgentJobTestSupport {

    static final String TENANT = "t_test";
    static final String REQUESTED_BY = "alice@example.com";

    private AgentJobTestSupport() {
    }

    static AgentJob queuedJob(int timeoutSeconds, int maxToolCalls) {
        return queuedJob(TENANT, REQUESTED_BY, timeoutSeconds, maxToolCalls);
    }

    static AgentJob queuedJob(String tenantId, String requestedBy, int timeoutSeconds, int maxToolCalls) {
        return AgentJob.queued(
            UUID.randomUUID(),
            tenantId,
            UUID.randomUUID(),
            "Summarise yesterday's support tickets",
            // subjectKey == requestedBy is the budget-escape guard, asserted
            // directly in AgentJobBudgetSubjectTest.
            requestedBy,
            requestedBy,
            Set.of("ROLE_user"),
            AgentJobOrigin.API,
            null,
            timeoutSeconds,
            1,
            maxToolCalls,
            null
        );
    }

    /**
     * A repository mock wired to behave like the real one for the calls an
     * execution makes: load, claim (which really does move the row to RUNNING),
     * the terminal save, the cancel-flag read, and every guarded status UPDATE
     * that park/resume/recovery use.
     *
     * <p>The mirroring is not decoration. Each of these is a conditional UPDATE
     * in production, so the worker's next read of the row comes back in the NEW
     * state; a mock that returned a rowcount without touching the entity would
     * leave it in the OLD state and make the next legitimate transition throw
     * {@link AgentJobIllegalTransitionException} for a reason that has nothing
     * to do with the behaviour under test.
     */
    static AgentJobRepository repositoryFor(AgentJob job) {
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        when(repository.claim(eq(job.getId()), anyString(), any(Instant.class), any(Instant.class)))
            .thenAnswer(invocation -> {
                job.markRunning(invocation.getArgument(1), invocation.getArgument(2));
                return 1;
            });
        when(repository.save(any(AgentJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.isCancelRequested(job.getId())).thenReturn(Optional.of(false));

        // RUNNING -> AWAITING_APPROVAL. Guarded, so a job that is no longer
        // RUNNING must report rowcount 0 rather than throwing - that is exactly
        // what the real statement does when a sweep got there first.
        when(repository.parkForApproval(
            eq(job.getId()), any(Instant.class), any(Instant.class), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> {
                if (job.getStatus() != AgentJobStatus.RUNNING) {
                    return 0;
                }
                job.parkForApproval(
                    invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3));
                return 1;
            });

        // AWAITING_APPROVAL -> RUNNING. The entity mirror cannot express the
        // fresh deadline_at / attempt_count the real UPDATE stamps (there is no
        // setter for either), which is fine: a test asserting on those must
        // assert on the repository interaction, not on this in-memory copy.
        when(repository.resumeClaim(eq(job.getId()), anyString(), any(Instant.class), any(Instant.class)))
            .thenAnswer(invocation -> {
                if (job.getStatus() != AgentJobStatus.AWAITING_APPROVAL) {
                    return 0;
                }
                job.resumeFromApproval(invocation.getArgument(1), invocation.getArgument(2));
                return 1;
            });

        when(repository.failIfAwaitingApproval(eq(job.getId()), anyString(), anyString(), any(Instant.class)))
            .thenAnswer(invocation -> {
                if (job.getStatus() != AgentJobStatus.AWAITING_APPROVAL) {
                    return 0;
                }
                job.fail(invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3));
                return 1;
            });

        when(repository.cancelIfAwaitingApproval(eq(job.getId()), anyString(), any(Instant.class)))
            .thenAnswer(invocation -> {
                if (job.getStatus() != AgentJobStatus.AWAITING_APPROVAL) {
                    return 0;
                }
                job.markCancelRequested(invocation.getArgument(1), invocation.getArgument(2));
                job.markCancelled(invocation.getArgument(2));
                return 1;
            });

        // RUNNING -> QUEUED, the durability policy's bounded re-queue.
        when(repository.requeueIfStillRunning(eq(job.getId()), any(Instant.class)))
            .thenAnswer(invocation -> {
                if (job.getStatus() != AgentJobStatus.RUNNING) {
                    return 0;
                }
                job.requeue(invocation.getArgument(1));
                return 1;
            });
        return repository;
    }

    static AgentJobProperties properties() {
        AgentJobProperties properties = new AgentJobProperties();
        properties.setMaxTurns(1);
        return properties;
    }
}
