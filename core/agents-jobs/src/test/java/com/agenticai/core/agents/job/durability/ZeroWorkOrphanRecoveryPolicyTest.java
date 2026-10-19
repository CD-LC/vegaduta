package com.agenticai.core.agents.job.durability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobOrphanRecoveryPolicy.Verdict;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The idempotency argument, as assertions.
 *
 * <p>Every FAIL case below is a case where re-running the job could charge the requester's budget a
 * second time or fire a side effect twice. There is no job-level cost ledger ({@code
 * agent_job.cost_usd} is never written), spend is metered per {@code AgentChatService#chat} call
 * against the real human's subject key, and no tool dedupes on a request id - so "provably zero
 * work" is the only safe re-queue condition, and each test here pins one clause of it.
 *
 * <p>The single REQUEUE case is the narrow one: a job whose previous attempt died between {@code
 * claim} and its first model call. It charged nothing because nothing had been called yet.
 */
class ZeroWorkOrphanRecoveryPolicyTest {

    private AgentJobProperties properties;
    private ToolCallLogRepository toolCallLogRepository;
    private ZeroWorkOrphanRecoveryPolicy policy;

    @BeforeEach
    void setUp() {
        properties = DurabilityTestSupport.properties();
        toolCallLogRepository = mock(ToolCallLogRepository.class);
        // Default: nothing was ever executed for this session.
        when(toolCallLogRepository.countByAgentIdAndSessionId(any(UUID.class), anyString()))
                .thenReturn(0L);
        policy =
                new ZeroWorkOrphanRecoveryPolicy(
                        properties, toolCallLogRepository, new SimpleMeterRegistry());
    }

    @Test
    void anOrphanThatNeverStartedATurnAndLoggedNoToolCallsIsRequeued() {
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();

        assertEquals(Verdict.REQUEUE, policy.decide(orphan));

        // The zero-work proof is a real query against THIS job's session, not an
        // assumption from turns_used alone.
        verify(toolCallLogRepository)
                .countByAgentIdAndSessionId(orphan.getAgentId(), orphan.getSessionId());
    }

    @Test
    void anOrphanThatHadAlreadyStartedATurnIsFailed() {
        // The most important assertion in this class. A started turn means a
        // model call may already have been billed to the requester even though
        // no answer came back - re-running charges them twice for one request.
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();
        orphan.incrementTurn();

        assertEquals(Verdict.FAIL, policy.decide(orphan));

        // Cheap in-memory clauses are evaluated BEFORE the database read, so a
        // decided job costs the sweep no query at all.
        verify(toolCallLogRepository, never())
                .countByAgentIdAndSessionId(any(UUID.class), anyString());
    }

    @Test
    void anOrphanWithAnyLoggedToolCallIsFailed() {
        // The independent side-effect check. turns_used now marks a turn as
        // STARTED before the model call (see AgentJobExecution#runTurns), so
        // this is no longer the only thing standing between a mid-turn kill and
        // a re-queue - but it stays, because tool side effects are not
        // idempotent and tool_call_log is a log, not a dedupe key: a job could
        // in principle be interrupted between its tool calls and its own turn
        // accounting in some future multi-turn shape.
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();
        when(toolCallLogRepository.countByAgentIdAndSessionId(
                        orphan.getAgentId(), orphan.getSessionId()))
                .thenReturn(1L);

        assertEquals(Verdict.FAIL, policy.decide(orphan));
    }

    @Test
    void anOrphanThatHasExhaustedItsAttemptsIsFailed() {
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();
        // A second claim, the way production reaches attempt_count 2:
        // RUNNING -> QUEUED (the re-queue) -> RUNNING.
        orphan.requeue(Instant.now());
        orphan.markRunning(DurabilityTestSupport.DEAD_REPLICA, Instant.now().minusSeconds(600));
        assertEquals(2, orphan.getAttemptCount());

        assertEquals(Verdict.FAIL, policy.decide(orphan));
    }

    @Test
    void oneMoreAttemptIsAllowedWhenTheCeilingIsRaised() {
        // Proves the previous test failed on the CEILING and not on the
        // re-queue/re-claim sequence itself.
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();
        orphan.requeue(Instant.now());
        orphan.markRunning(DurabilityTestSupport.DEAD_REPLICA, Instant.now().minusSeconds(600));
        properties.getDurability().setMaxAttempts(3);

        assertEquals(Verdict.REQUEUE, policy.decide(orphan));
    }

    @Test
    void anOrphanOlderThanTheQueueTtlIsFailedRatherThanRequeuedIntoInstantExpiry() {
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();
        // A production-shaped expiry: the default one-hour TTL and a row
        // submitted two hours ago. Backdating the row rather than collapsing the
        // TTL to zero matters - the cutoff test is a STRICT isBefore, matching
        // AgentJobRunner:131 exactly, so a zero TTL puts createdAt and the cutoff
        // on the same clock tick and the verdict becomes clock-resolution
        // dependent (it flips to REQUEUE on a coarse clock).
        properties.setQueueTtl(Duration.ofHours(1));
        DurabilityTestSupport.backdateCreatedAt(orphan, Instant.now().minus(Duration.ofHours(2)));

        // Re-queuing would put the row straight back in front of the dispatch
        // tick, which expires anything past the TTL with QUEUE_TTL_EXPIRED. One
        // honest verdict beats two contradictory ones.
        assertEquals(Verdict.FAIL, policy.decide(orphan));
    }

    @Test
    void anOrphanWithACancelRequestIsFailedRatherThanRequeued() {
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();
        orphan.markCancelRequested("bob@example.com", Instant.now());

        assertEquals(Verdict.FAIL, policy.decide(orphan));
    }

    @Test
    void turningOffRequeueRestoresTheShippedFailLoudlyBehaviourExactly() {
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();
        properties.getDurability().setRequeueZeroWorkOrphans(false);

        assertEquals(Verdict.FAIL, policy.decide(orphan));
        verify(toolCallLogRepository, never())
                .countByAgentIdAndSessionId(any(UUID.class), anyString());
    }

    @Test
    void aFailingToolCallLookupFailsTheJobRatherThanAssumingItDidNoWork() {
        // An UNPROVABLE zero-work claim is not a zero-work claim. Returning
        // REQUEUE on a DB blip would re-run jobs that had already spent money.
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();
        when(toolCallLogRepository.countByAgentIdAndSessionId(
                        orphan.getAgentId(), orphan.getSessionId()))
                .thenThrow(new IllegalStateException("connection reset"));

        assertEquals(Verdict.FAIL, policy.decide(orphan));
    }
}
