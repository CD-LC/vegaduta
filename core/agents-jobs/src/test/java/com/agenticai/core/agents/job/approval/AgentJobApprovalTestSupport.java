package com.agenticai.core.agents.job.approval;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobExecutor;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.approval.ApprovalRequest;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Fixtures for the approval-parking unit tests.
 *
 * <p>Everything here is a plain Mockito mock: no Spring context, no database.
 * That is this module's standing rule - a full {@code mvn test} in {@code
 * core/app} boots a real Postgres and MCP, and these have to stay runnable in
 * seconds.
 *
 * <p><b>Why this duplicates part of {@code AgentJobTestSupport}.</b> That class
 * is package-private in {@code com.agenticai.core.agents.job} and Java package
 * access is not hierarchical, so a test in {@code ...job.approval} genuinely
 * cannot see it. The repository-mirroring idiom below is copied from it
 * deliberately rather than reinvented, and the reason it exists is the same:
 * every write in {@link AgentJobRepository} is a conditional UPDATE issued by
 * the database, so in production the worker's next read of the row comes back in
 * the NEW state. A mock that returned a rowcount without touching the entity
 * would leave it in the OLD state and make the next legitimate transition throw
 * {@code AgentJobIllegalTransitionException} for a reason that has nothing to do
 * with the behaviour under test.
 */
final class AgentJobApprovalTestSupport {

    static final String TENANT = "t_test";
    static final String REQUESTED_BY = "alice@example.com";

    /** The prefix {@code GuardedToolCallback} records for a gated agent tool call. */
    static final String TOOL_ACTION_TYPE = "mcp_tool.send_message";

    private AgentJobApprovalTestSupport() {
    }

    static AgentJob queuedJob(int timeoutSeconds, int maxToolCalls) {
        return AgentJob.queued(
            UUID.randomUUID(),
            TENANT,
            UUID.randomUUID(),
            "Send the weekly summary to the ops channel",
            // subjectKey == requestedBy is the budget-escape guard: a synthetic
            // per-job subject would mint a fresh daily ceiling per submission.
            REQUESTED_BY,
            REQUESTED_BY,
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
     * A job in exactly the state a worker leaves behind when it parks: one turn
     * spent, {@code park_count = 1}, no owner, no heartbeat, an approval
     * deadline in the future.
     */
    static AgentJob parkedJob(Instant approvalDeadlineAt) {
        AgentJob job = queuedJob(600, 15);
        job.markRunning("dead-replica:9", Instant.now().minusSeconds(120));
        // The turn that tripped the gate was still a turn, and turns_used is
        // durable - which is why the resumed run needs the max_turns +
        // park_count allowance to have anything left to do.
        job.incrementTurn();
        job.parkForApproval(Instant.now().minusSeconds(60), approvalDeadlineAt);
        return job;
    }

    /** The PENDING {@code approval_request} row a gated tool call leaves for {@code job}. */
    static ApprovalRequest pendingRequestFor(AgentJob job) {
        return new ApprovalRequest(
            TOOL_ACTION_TYPE,
            "Send a message to #ops",
            "{\"channel\":\"#ops\"}",
            AgentJobSession.requestedByLabel(job.getAgentId(), job.getId())
        );
    }

    static ApprovalRequest rejectedRequestFor(AgentJob job) {
        ApprovalRequest request = pendingRequestFor(job);
        request.markRejected("admin@example.com", "we do not message that channel on weekends");
        return request;
    }

    /**
     * A repository mock that behaves like the real one for every call the park,
     * resume and sweep paths make. Each guarded UPDATE reports rowcount 0 when
     * the row is no longer in the state it guards on - which is exactly what the
     * real statement does when a sweep or a worker got there first, and is the
     * behaviour several tests below depend on.
     */
    static AgentJobRepository repositoryFor(AgentJob job) {
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        when(repository.save(any(AgentJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.isCancelRequested(job.getId())).thenReturn(Optional.of(false));

        when(repository.claim(eq(job.getId()), anyString(), any(Instant.class), any(Instant.class)))
            .thenAnswer(invocation -> {
                if (job.getStatus() != AgentJobStatus.QUEUED) {
                    return 0;
                }
                job.markRunning(invocation.getArgument(1), invocation.getArgument(2));
                return 1;
            });

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

        // The entity mirror cannot express the fresh deadline_at / attempt_count
        // the real UPDATE stamps (there is no setter for either), which is fine:
        // a test asserting on those asserts on the repository interaction.
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

        return repository;
    }

    static AgentJobProperties properties() {
        AgentJobProperties properties = new AgentJobProperties();
        properties.setMaxTurns(1);
        return properties;
    }

    /**
     * Stops a real {@code AgentJobExecutor}'s pool and heartbeat scheduler.
     *
     * <p>Reflection, and not by choice: the shutdown hook is a package-private
     * {@code @PreDestroy} method in {@code com.agenticai.core.agents.job}, and
     * the fan-out contract puts this test in {@code ...job.approval}. Skipping it
     * is not an option - {@code CustomizableThreadFactory} creates NON-daemon
     * threads and the heartbeat scheduler starts one in the constructor, so a
     * leaked executor keeps the Surefire JVM alive after the suite finishes.
     */
    static void shutdown(AgentJobExecutor executor) {
        try {
            Method shutdown = AgentJobExecutor.class.getDeclaredMethod("shutdown");
            shutdown.setAccessible(true);
            shutdown.invoke(executor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not shut down the agent job executor under test", e);
        }
    }

    /**
     * Polls {@code condition} until it holds or the budget runs out. Used instead
     * of a latch where the thing being waited for is the ABSENCE of something (a
     * released worker slot), which nothing can count down.
     */
    static boolean awaitTrue(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
    }
}
