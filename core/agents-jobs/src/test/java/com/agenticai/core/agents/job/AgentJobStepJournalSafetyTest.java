package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.tools.StepJournal;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * L1 enable-blockers on the resume path: a COMPLETED turn with no result is not a resume point
 * (blocker 2), a DURABLE tool call in flight forbids re-running the turn (blocker 1), and an
 * approval gate the dead attempt tripped still parks the resumed job (blocker 3).
 */
class AgentJobStepJournalSafetyTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private final AgentChatService chatService = mock(AgentChatService.class);
    private final StepJournal journal = mock(StepJournal.class);
    private final ToolCallLogRepository toolCallLogRepository = mock(ToolCallLogRepository.class);

    private static AgentJobProperties properties(boolean rerun) {
        AgentJobProperties p = AgentJobTestSupport.properties();
        p.getDurability().setStepJournalEnabled(true);
        p.getDurability().setResumeFromJournal(true);
        p.getDurability().setResumeRerunIncompleteTurn(rerun);
        return p;
    }

    @SuppressWarnings("unchecked")
    private void run(
            AgentJob job,
            AgentJobRepository repository,
            AgentJobProperties properties,
            AgentJobApprovalPark park) {
        when(journal.isEnabled()).thenReturn(true);
        when(journal.isDurableCallTrackingActive()).thenReturn(true);
        when(toolCallLogRepository.countByAgentIdAndSessionId(any(), any())).thenReturn(0L);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("fresh answer"));
        ObjectProvider<AgentJobOutputContract> contract = mock(ObjectProvider.class);
        when(contract.getIfAvailable(any(java.util.function.Supplier.class)))
                .thenReturn(AgentJobOutputContract.PASS_THROUGH);
        ObjectProvider<AgentJobApprovalPark> parkProvider = mock(ObjectProvider.class);
        when(parkProvider.getIfAvailable(any(java.util.function.Supplier.class))).thenReturn(park);
        AgentJobExecution execution =
                new AgentJobExecution(
                        repository,
                        chatService,
                        toolCallLogRepository,
                        properties,
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class),
                        contract,
                        parkProvider);
        execution.setStepJournal(journal);
        execution.run(job.getId(), AgentJobTestSupport.TENANT);
    }

    private static AgentJob requeuedOrphanWithOneStartedTurn() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        job.markRunning("pod-1", Instant.now());
        job.incrementTurn();
        job.requeue(Instant.now());
        return job;
    }

    @Test
    void aCompletedTurnWithABlankResultIsNeverFinishedAsAnEmptySuccess() {
        AgentJob job = requeuedOrphanWithOneStartedTurn();
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        when(journal.find(StepJournal.runIdForJob(job.getId()), StepJournal.turnStepId(0)))
                .thenReturn(Optional.of(new StepJournal.Step(StepJournal.COMPLETED, "")));

        run(job, repository, properties(false), AgentJobApprovalPark.NEVER_PARKS);

        verify(chatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        assertEquals(AgentJobStatus.FAILED, job.getStatus());
        assertEquals("EXECUTION_FAILED", job.getErrorCode());
    }

    @Test
    void aCompletedTurnWithANullResultFallsBackToTheRerunRuleWhenRerunIsOn() {
        AgentJob job = requeuedOrphanWithOneStartedTurn();
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        when(journal.find(StepJournal.runIdForJob(job.getId()), StepJournal.turnStepId(0)))
                .thenReturn(Optional.of(new StepJournal.Step(StepJournal.COMPLETED, null)));

        run(job, repository, properties(true), AgentJobApprovalPark.NEVER_PARKS);

        verify(chatService).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
        assertEquals("fresh answer", job.getResultText());
    }

    @Test
    void aTurnWithADurableToolCallInFlightIsNotRerun() {
        AgentJob job = requeuedOrphanWithOneStartedTurn();
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        when(journal.find(StepJournal.runIdForJob(job.getId()), StepJournal.turnStepId(0)))
                .thenReturn(Optional.of(new StepJournal.Step(StepJournal.STARTED, null)));
        when(journal.hasIncompleteDurableCall(job.getId())).thenReturn(true);

        run(job, repository, properties(true), AgentJobApprovalPark.NEVER_PARKS);

        verify(chatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        assertEquals(AgentJobStatus.FAILED, job.getStatus());
        assertTrue(job.getErrorMessage().contains("may already have taken effect"));
    }

    @Test
    void aResumedJobStillParksOnTheApprovalItsDeadAttemptTripped() {
        AgentJob job = requeuedOrphanWithOneStartedTurn();
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        Instant turnStart = Instant.now().minusSeconds(600);
        when(journal.find(StepJournal.runIdForJob(job.getId()), StepJournal.turnStepId(0)))
                .thenReturn(
                        Optional.of(
                                new StepJournal.Step(StepJournal.COMPLETED, "answer", turnStart)));
        AtomicReference<Instant> boundary = new AtomicReference<>();
        AgentJobApprovalPark park =
                new AgentJobApprovalPark() {
                    @Override
                    public Optional<ParkDecision> parkIfGated(AgentJob j, Instant turnStartedAt) {
                        boundary.set(turnStartedAt);
                        return Optional.of(
                                new ParkDecision(Instant.now().plusSeconds(3600), null, "gated"));
                    }
                };

        run(job, repository, properties(false), park);

        assertNotNull(boundary.get());
        assertEquals(
                turnStart,
                boundary.get(),
                "the detector must look back to the journaled start of the resumed turn, not this"
                        + " attempt's start");
        assertEquals(AgentJobStatus.AWAITING_APPROVAL, job.getStatus());
        assertNull(job.getResultText());
    }

    @Test
    void withoutAJournalTimestampTheParkBoundaryIsThisAttemptsStart() {
        AgentJob job = requeuedOrphanWithOneStartedTurn();
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        when(journal.find(anyString(), anyString()))
                .thenReturn(Optional.of(new StepJournal.Step(StepJournal.COMPLETED, "answer")));
        AtomicReference<Instant> boundary = new AtomicReference<>();
        Instant before = Instant.now();
        AgentJobApprovalPark park =
                new AgentJobApprovalPark() {
                    @Override
                    public Optional<ParkDecision> parkIfGated(AgentJob j, Instant turnStartedAt) {
                        boundary.set(turnStartedAt);
                        return Optional.empty();
                    }
                };

        run(job, repository, properties(false), park);

        assertTrue(!boundary.get().isBefore(before));
    }
}
