package com.agenticai.core.agents.job.durability;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobProperties;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Fixtures for the durability tests.
 *
 * <p>Deliberately a near-copy of {@code AgentJobTestSupport} rather than a reuse
 * of it: that class is package-private in {@code ...agents.job} and is owned by
 * another session in this fan-out, so it cannot be widened from here. Only the
 * two builders this package actually needs are mirrored; the shared repository
 * stub is not, because these tests assert on GUARDED REPOSITORY CALLS (which
 * write is issued, with which arguments) rather than on the resulting in-memory
 * entity state, so a plain Mockito mock is both sufficient and more honest about
 * what production actually does.
 *
 * <p>Everything here is plain Mockito/JUnit with no Spring context and no
 * database - the standing rule for {@code core/agents} tests.
 */
final class DurabilityTestSupport {

    static final String TENANT = "t_test";
    static final String REQUESTED_BY = "alice@example.com";
    static final String DEAD_REPLICA = "dead-replica:9";

    private DurabilityTestSupport() {
    }

    /**
     * A job that was claimed and then abandoned: RUNNING, {@code
     * attempt_count = 1}, {@code turns_used = 0}, heartbeat ten minutes stale.
     * This is the shape a SIGKILL between {@code claim} and the first model call
     * leaves behind - i.e. the one case PR-4 is allowed to re-queue.
     */
    static AgentJob abandonedRunningJob() {
        AgentJob job = queuedJob();
        job.markRunning(DEAD_REPLICA, Instant.now().minusSeconds(600));
        return job;
    }

    static AgentJob queuedJob() {
        return AgentJob.queued(
            UUID.randomUUID(),
            TENANT,
            UUID.randomUUID(),
            "Summarise yesterday's support tickets",
            // subjectKey == requestedBy: a job spends the REAL human's budget,
            // which is exactly why a re-run is a double charge and not free.
            REQUESTED_BY,
            REQUESTED_BY,
            Set.of("ROLE_user"),
            AgentJobOrigin.API,
            null,
            60,
            1,
            15,
            null
        );
    }

    static AgentJobProperties properties() {
        AgentJobProperties properties = new AgentJobProperties();
        properties.setMaxTurns(1);
        return properties;
    }

    /**
     * Rewinds {@code created_at}, which {@link AgentJob#queued} stamps with
     * {@code Instant.now()} and exposes no setter for.
     *
     * <p>Reflection rather than a zero-length {@code queue-ttl}: the policy's
     * cutoff test is {@code createdAt.isBefore(now - queueTtl)}, deliberately the
     * same strict comparison production uses at {@code AgentJobRunner:131}, so at
     * a TTL of zero the two timestamps can land on the same clock tick and the
     * verdict flips with the platform's clock granularity. Ageing the row instead
     * reproduces what actually happens in production - a job submitted before the
     * cutoff - and gives the same answer on every machine.
     */
    static AgentJob backdateCreatedAt(AgentJob job, Instant createdAt) {
        try {
            Field field = AgentJob.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(job, createdAt);
            return job;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AgentJob.createdAt is no longer a declared field", e);
        }
    }
}
