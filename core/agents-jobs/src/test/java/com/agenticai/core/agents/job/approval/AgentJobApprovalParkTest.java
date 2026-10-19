package com.agenticai.core.agents.job.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobApprovalPark;
import com.agenticai.core.agents.job.AgentJobExecution;
import com.agenticai.core.agents.job.AgentJobExecutor;
import com.agenticai.core.agents.job.AgentJobOutputContract;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * PR-5's central claim: <b>a job that stops at a human approval gate parks, and a parked job holds
 * no worker.</b>
 *
 * <p>Before this, a gated turn "completed" - {@code GuardedToolCallback} returns an explanatory
 * string to the model instead of performing the action - and the job was written SUCCEEDED with
 * prose about something that never happened. The regression these tests lock is that a gated job is
 * now a STATUS, not a paragraph a caller has to parse.
 *
 * <p>{@link #aParkedJobReleasesItsWorkerSlot()} is the one that costs real threads, and it earns
 * them: "releases its worker" is a claim about {@code AgentJobExecutor}'s pool, and the only honest
 * way to assert it is to run a job on a real pool and watch the slot come back.
 */
class AgentJobApprovalParkTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------ the park itself

    @Test
    void aTurnThatLeavesAPendingApprovalRequestParksTheJobInsteadOfSucceedingWithProse() {
        AgentJob job = AgentJobApprovalTestSupport.queuedJob(600, 15);
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(job);
        Fixture fixture = new Fixture(job, repository);

        // The realistic sequence: the gate records its PENDING row DURING the
        // turn, so the row only exists once the model call has been made.
        fixture.recordApprovalRequestDuringTheTurn();

        fixture.execution().run(job.getId(), AgentJobApprovalTestSupport.TENANT);

        assertEquals(AgentJobStatus.AWAITING_APPROVAL, job.getStatus());
        assertNotNull(job.getParkedAt(), "a parked job must record when it parked");
        assertNotNull(
                job.getApprovalDeadlineAt(), "a park with no deadline is a job that waits forever");
        assertEquals(1, job.getParkCount());
        // Both NULLed by the park, and each for its own reason: a stale owner
        // makes the orphan sweep's "is another replica running this?" question
        // unanswerable, and a stale heartbeat keeps the row matching that
        // sweep's query while it sits harmlessly parked.
        assertNull(job.getOwnerInstance());
        assertNull(job.getHeartbeatAt());
        // The whole point: no terminal write happened, so nothing was persisted
        // as a success and no result prose was handed to a caller.
        assertNull(job.getResultText());
        assertNull(job.getFinishedAt());
        verify(repository)
                .parkForApproval(
                        eq(job.getId()),
                        any(Instant.class),
                        any(Instant.class),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void theParkDeadlineIsTheConfiguredParkTimeoutFromNow() {
        AgentJob job = AgentJobApprovalTestSupport.queuedJob(600, 15);
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(job);
        AgentJobProperties properties = AgentJobApprovalTestSupport.properties();
        properties.getApproval().setParkTimeout(Duration.ofHours(6));
        Fixture fixture = new Fixture(job, repository, properties);
        fixture.recordApprovalRequestDuringTheTurn();

        Instant before = Instant.now();
        fixture.execution().run(job.getId(), AgentJobApprovalTestSupport.TENANT);

        ArgumentCaptor<Instant> deadline = ArgumentCaptor.forClass(Instant.class);
        verify(repository)
                .parkForApproval(
                        eq(job.getId()),
                        any(Instant.class),
                        deadline.capture(),
                        org.mockito.ArgumentMatchers.any());
        assertTrue(
                !deadline.getValue().isBefore(before.plus(Duration.ofHours(6)).minusSeconds(30)),
                "the park deadline must come from app.agent-jobs.approval.park-timeout, not from"
                        + " the execution deadline");
    }

    @Test
    void aJobWithNoPendingApprovalRequestFinishesNormally() {
        AgentJob job = AgentJobApprovalTestSupport.queuedJob(600, 15);
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(job);
        Fixture fixture = new Fixture(job, repository);
        // No gate: the lookup keeps returning nothing.

        fixture.execution().run(job.getId(), AgentJobApprovalTestSupport.TENANT);

        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
        verify(repository, never())
                .parkForApproval(
                        any(),
                        any(Instant.class),
                        any(Instant.class),
                        org.mockito.ArgumentMatchers.any());
    }

    /**
     * The acceptance criterion stated mechanically. A parked job that still pinned a pool thread
     * would have failed PR-5 no matter what its row said, so this runs on a REAL {@link
     * AgentJobExecutor} and asserts the slot comes back.
     */
    @Test
    void aParkedJobReleasesItsWorkerSlot() throws Exception {
        AgentJob job = AgentJobApprovalTestSupport.queuedJob(600, 15);
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(job);
        AgentJobProperties properties = AgentJobApprovalTestSupport.properties();
        Fixture fixture = new Fixture(job, repository, properties);
        fixture.recordApprovalRequestDuringTheTurn();

        AgentJobExecutor executor =
                new AgentJobExecutor(repository, fixture.execution(), properties);
        try {
            assertTrue(executor.tryDispatch(job.getId(), AgentJobApprovalTestSupport.TENANT));
            assertTrue(
                    AgentJobApprovalTestSupport.awaitTrue(
                            () -> executor.activeCount() == 0, 20_000L),
                    "the parked job never released its worker - the pool would starve while a human"
                            + " thinks");

            assertEquals(AgentJobStatus.AWAITING_APPROVAL, job.getStatus());
            assertEquals(0, executor.activeCount());
            assertFalse(executor.isActiveHere(job.getId()));
            assertFalse(executor.isSaturated());
        } finally {
            AgentJobApprovalTestSupport.shutdown(executor);
        }
    }

    // ------------------------------------------------------ the detector's filters

    @Test
    void anApprovalRequestFromBeforeThisTurnIsNotAGate() {
        AgentJob job = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        ApprovalRequest stale = AgentJobApprovalTestSupport.pendingRequestFor(job);
        AgentJobApprovalLookup lookup =
                lookupReturning(job, ApprovalStatus.PENDING, List.of(stale));

        Optional<AgentJobApprovalPark.ParkDecision> decision =
                detector(lookup, AgentJobApprovalTestSupport.properties())
                        // A turn that started AFTER the request was recorded cannot have
                        // caused it. Without this filter a job would park forever on the
                        // corpse of a gate nobody is going to decide.
                        .parkIfGated(job, Instant.now().plusSeconds(60));

        assertTrue(decision.isEmpty());
    }

    @Test
    void aDecidedApprovalRequestIsNotAGate() {
        AgentJob job = AgentJobApprovalTestSupport.queuedJob(600, 15);
        // The lookup only ever returns PENDING rows; a rejected/approved one
        // simply is not in the result set.
        AgentJobApprovalLookup lookup = lookupReturning(job, ApprovalStatus.PENDING, List.of());

        assertTrue(
                detector(lookup, AgentJobApprovalTestSupport.properties())
                        .parkIfGated(job, Instant.now().minusSeconds(30))
                        .isEmpty());
    }

    @Test
    void aJobAtItsParkCeilingParksWithADeadDeadlineSoTheSweepCanFailIt() {
        AgentJob job = AgentJobApprovalTestSupport.queuedJob(600, 15);
        job.markRunning("host-a:1", Instant.now());
        // Three parks already spent, which is the default ceiling.
        for (int i = 0; i < 3; i++) {
            job.parkForApproval(Instant.now(), Instant.now().plusSeconds(3600));
            job.resumeFromApproval("host-a:1", Instant.now());
        }
        ApprovalRequest fresh = AgentJobApprovalTestSupport.pendingRequestFor(job);
        AgentJobApprovalLookup lookup =
                lookupReturning(job, ApprovalStatus.PENDING, List.of(fresh));

        Optional<AgentJobApprovalPark.ParkDecision> decision =
                detector(lookup, AgentJobApprovalTestSupport.properties())
                        .parkIfGated(job, Instant.now().minusSeconds(30));

        // NOT empty: returning empty would let the job finalize and succeed with
        // prose about an action that never ran, which is the exact bug PR-5
        // removes. A park with an already-dead deadline hands it to the sweep,
        // the single writer of a parked job's terminal status.
        assertTrue(decision.isPresent());
        assertTrue(
                decision.get().approvalDeadlineAt().isBefore(Instant.now()),
                "the refusal park must be immediately overdue so the approval sweep terminalises it"
                        + " on the next tick");
    }

    @Test
    void aFailingApprovalLookupLetsTheJobFinishRatherThanKillingIt() {
        AgentJob job = AgentJobApprovalTestSupport.queuedJob(600, 15);
        AgentJobApprovalLookup lookup = mock(AgentJobApprovalLookup.class);
        when(lookup.findByRequestedByAndStatusOrderByCreatedAtAsc(
                        anyString(), any(ApprovalStatus.class)))
                .thenThrow(new IllegalStateException("connection reset"));

        // Fail OPEN. The model call has already been billed by the time this
        // runs; a detection blip must not destroy the work it paid for.
        assertTrue(
                detector(lookup, AgentJobApprovalTestSupport.properties())
                        .parkIfGated(job, Instant.now().minusSeconds(30))
                        .isEmpty());
    }

    @Test
    void theGateIsFoundByTheLabelGuardedToolCallbackActuallyWrites() {
        AgentJob job = AgentJobApprovalTestSupport.queuedJob(600, 15);
        // Locked here as well as in AgentJobSessionTest because THIS is the
        // consumer: if the two formats ever drift, park detection silently stops
        // finding anything and every gated job succeeds with prose again.
        assertEquals(
                "agent:" + job.getAgentId() + ":session:job:" + job.getId(),
                AgentJobSession.requestedByLabel(job.getAgentId(), job.getId()));
    }

    // ---------------------------------------------------------------- resume path

    @Test
    void aParkedJobResumesOnAFreshTurnAndReachesSucceeded() {
        AgentJob job = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(job);
        Fixture fixture = new Fixture(job, repository);

        fixture.execution().run(job.getId(), AgentJobApprovalTestSupport.TENANT);

        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
        assertEquals("the answer", job.getResultText());
        // AWAITING_APPROVAL -> RUNNING goes through resumeClaim, never claim:
        // claim is QUEUED-guarded and would have matched nothing.
        verify(repository)
                .resumeClaim(eq(job.getId()), anyString(), any(Instant.class), any(Instant.class));
        verify(repository, never())
                .claim(eq(job.getId()), anyString(), any(Instant.class), any(Instant.class));
        // Two turns' worth of allowance (max_turns 1 + park_count 1), of which
        // the parked turn already spent one - so exactly one more ran. Without
        // that allowance the resumed job would run ZERO turns and land
        // succeed(null, ...), a silent empty success right after a human took
        // the trouble to approve something.
        assertEquals(2, job.getTurnsUsed());
    }

    @Test
    void aResumeStampsAFreshDeadlineRatherThanReusingTheParkedOne() {
        // A resume that reused the pre-park deadline would be killed by the
        // deadline sweep within 15 seconds, because that deadline is hours in
        // the past for any job that actually waited for a human.
        AgentJob job = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(job);
        Fixture fixture = new Fixture(job, repository);
        Instant before = Instant.now();

        fixture.execution().run(job.getId(), AgentJobApprovalTestSupport.TENANT);

        ArgumentCaptor<Instant> deadline = ArgumentCaptor.forClass(Instant.class);
        verify(repository)
                .resumeClaim(eq(job.getId()), anyString(), any(Instant.class), deadline.capture());
        assertTrue(
                deadline.getValue().isAfter(before),
                "the resumed job was handed an already-expired deadline");
    }

    // -------------------------------------------------------------------- helpers

    private static AgentJobApprovalParkDetector detector(
            AgentJobApprovalLookup lookup, AgentJobProperties properties) {
        return new AgentJobApprovalParkDetector(lookup, properties, new SimpleMeterRegistry());
    }

    private static AgentJobApprovalLookup lookupReturning(
            AgentJob job, ApprovalStatus status, List<ApprovalRequest> requests) {
        AgentJobApprovalLookup lookup = mock(AgentJobApprovalLookup.class);
        when(lookup.findByRequestedByAndStatusOrderByCreatedAtAsc(
                        eq(AgentJobSession.requestedByLabel(job.getAgentId(), job.getId())),
                        eq(status)))
                .thenReturn(requests);
        return lookup;
    }

    /**
     * An {@link AgentJobExecution} wired over one job with the real park detector behind
     * Foundation's {@code ObjectProvider} seam, and the output contract left as pass-through
     * (structured output is a sibling PR).
     */
    private static final class Fixture {

        private final AgentJob job;
        private final AgentChatService chatService = mock(AgentChatService.class);
        private final AgentJobApprovalLookup lookup = mock(AgentJobApprovalLookup.class);
        private final AgentJobExecution execution;
        private final AtomicReference<ApprovalRequest> recorded = new AtomicReference<>();

        private Fixture(AgentJob job, AgentJobRepository repository) {
            this(job, repository, AgentJobApprovalTestSupport.properties());
        }

        private Fixture(
                AgentJob job, AgentJobRepository repository, AgentJobProperties properties) {
            this.job = job;
            when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                    .thenAnswer(invocation -> Flux.just("the answer"));
            when(lookup.findByRequestedByAndStatusOrderByCreatedAtAsc(
                            anyString(), any(ApprovalStatus.class)))
                    .thenAnswer(
                            invocation -> {
                                ApprovalRequest request = recorded.get();
                                ApprovalStatus wanted =
                                        invocation.getArgument(1, ApprovalStatus.class);
                                return request != null && request.getStatus() == wanted
                                        ? List.of(request)
                                        : List.of();
                            });

            AgentJobApprovalParkDetector detector =
                    new AgentJobApprovalParkDetector(lookup, properties, new SimpleMeterRegistry());
            this.execution =
                    new AgentJobExecution(
                            repository,
                            chatService,
                            mock(ToolCallLogRepository.class),
                            properties,
                            new SimpleMeterRegistry(),
                            mock(AgentJobProgressService.class),
                            provider(AgentJobOutputContract.PASS_THROUGH),
                            provider((AgentJobApprovalPark) detector));
        }

        /**
         * Makes the gate behave the way it really does: the PENDING row is written DURING the turn,
         * so its {@code created_at} genuinely falls after the turn started. Building it up front
         * would put it before the turn and the detector would (correctly) ignore it.
         */
        private void recordApprovalRequestDuringTheTurn() {
            when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                    .thenAnswer(
                            invocation -> {
                                recorded.compareAndSet(
                                        null, AgentJobApprovalTestSupport.pendingRequestFor(job));
                                return Flux.just("I have requested approval to send that message.");
                            });
        }

        private AgentJobExecution execution() {
            return execution;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(ArgumentMatchers.<Supplier<T>>any())).thenReturn(instance);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }
}
