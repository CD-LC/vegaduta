package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * {@code AgentJobExecution#runTurns} with the L1 step journal: completed-turn reuse, re-running a
 * STARTED-only turn without a second {@code turns_used} increment, the approval-park resume that
 * must NOT replay the parked turn, and flag-off equals the pre-journal loop.
 */
class AgentJobStepJournalResumeTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private final AgentChatService chatService = mock(AgentChatService.class);
    private final StepJournal journal = mock(StepJournal.class);
    private final ToolCallLogRepository toolCallLogRepository = mock(ToolCallLogRepository.class);

    private AgentJobProperties properties(boolean resume, boolean rerun) {
        AgentJobProperties p = AgentJobTestSupport.properties();
        p.getDurability().setStepJournalEnabled(true);
        p.getDurability().setResumeFromJournal(resume);
        p.getDurability().setResumeRerunIncompleteTurn(rerun);
        return p;
    }

    private void run(
            AgentJob job,
            AgentJobRepository repository,
            AgentJobProperties properties,
            boolean journalOn) {
        when(journal.isEnabled()).thenReturn(journalOn);
        when(journal.isDurableCallTrackingActive()).thenReturn(true);
        when(toolCallLogRepository.countByAgentIdAndSessionId(any(), any())).thenReturn(0L);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("fresh answer"));
        AgentJobExecution execution =
                new AgentJobExecution(
                        repository,
                        chatService,
                        toolCallLogRepository,
                        properties,
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class));
        execution.setStepJournal(journal);
        execution.run(job.getId(), AgentJobTestSupport.TENANT);
    }

    /**
     * An orphan the recovery policy re-queued: one turn already started, back to QUEUED,
     * attempt_count 1.
     */
    private static AgentJob requeuedOrphanWithOneStartedTurn() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        job.markRunning("pod-1", Instant.now());
        job.incrementTurn();
        job.requeue(Instant.now());
        return job;
    }

    @Test
    void aCompletedJournaledTurnIsReusedAndNoModelCallRuns() {
        AgentJob job = requeuedOrphanWithOneStartedTurn();
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        when(journal.find(StepJournal.runIdForJob(job.getId()), StepJournal.turnStepId(0)))
                .thenReturn(
                        Optional.of(
                                new StepJournal.Step(StepJournal.COMPLETED, "journaled answer")));

        run(job, repository, properties(true, false), true);

        verify(chatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        verify(repository, never()).incrementTurnsUsed(job.getId());
        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
        assertEquals(
                "journaled answer",
                job.getResultText(),
                "at turns_used == max_turns the loop is skipped and the journaled result must be"
                        + " the answer, not an empty success");
    }

    @Test
    void aStartedOnlyTurnIsRerunWithoutASecondTurnsUsedIncrement() {
        AgentJob job = requeuedOrphanWithOneStartedTurn();
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        when(journal.find(StepJournal.runIdForJob(job.getId()), StepJournal.turnStepId(0)))
                .thenReturn(Optional.of(new StepJournal.Step(StepJournal.STARTED, null)));

        run(job, repository, properties(true, true), true);

        verify(chatService, times(1)).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        verify(repository, never()).incrementTurnsUsed(job.getId());
        verify(journal)
                .begin(
                        StepJournal.runIdForJob(job.getId()),
                        StepJournal.turnStepId(0),
                        StepJournal.KIND_TURN);
        verify(journal)
                .complete(
                        StepJournal.runIdForJob(job.getId()),
                        StepJournal.turnStepId(0),
                        StepJournal.KIND_TURN,
                        "fresh answer");
        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
        assertEquals("fresh answer", job.getResultText());
    }

    @Test
    void aStartedOnlyTurnIsNotRerunWhenRerunIsOff() {
        AgentJob job = requeuedOrphanWithOneStartedTurn();
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        when(journal.find(anyString(), anyString()))
                .thenReturn(Optional.of(new StepJournal.Step(StepJournal.STARTED, null)));

        run(job, repository, properties(true, false), true);

        verify(chatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void anApprovalParkResumeNeverConsultsTheJournalAndRunsExactlyTheOneGrantedTurn() {
        // Started a turn, parked for approval (turn exited mid-flight: STARTED-only), a human
        // approved.
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        job.markRunning("pod-1", Instant.now());
        job.incrementTurn();
        job.parkForApproval(Instant.now(), Instant.now().plusSeconds(3600), "needs approval");
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        when(journal.find(anyString(), anyString()))
                .thenReturn(Optional.of(new StepJournal.Step(StepJournal.STARTED, null)));

        run(job, repository, properties(true, true), true);

        verify(journal, never()).find(anyString(), anyString());
        verify(chatService, times(1)).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        verify(repository, times(1)).incrementTurnsUsed(job.getId());
        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
    }

    @Test
    void aFreshJobPurgesStaleRowsThenRunsTheOldLoopWithJournaling() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);

        run(job, repository, properties(true, true), true);

        verify(journal).purgeJobRun(job.getId());
        verify(journal, never()).find(anyString(), anyString());
        verify(chatService, times(1)).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        verify(repository, times(1)).incrementTurnsUsed(job.getId());
        verify(journal)
                .complete(
                        StepJournal.runIdForJob(job.getId()),
                        StepJournal.turnStepId(0),
                        StepJournal.KIND_TURN,
                        "fresh answer");
    }

    @Test
    void withTheJournalOffTheLoopIsExactlyTheOldOneAndTouchesNothing() {
        AgentJob job = requeuedOrphanWithOneStartedTurn();
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);

        run(job, repository, properties(true, true), false);

        verify(journal, never()).find(anyString(), anyString());
        verify(journal, never()).begin(anyString(), anyString(), anyString());
        verify(journal, never()).complete(anyString(), anyString(), anyString(), any());
        verify(journal, never()).purgeJobRun(any());
        // turns_used (1) == max_turns (1): the legacy loop runs zero turns; that is the pre-journal
        // behaviour
        // and is exactly why the journal path above exists.
        verify(chatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }
}
