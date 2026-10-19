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
import com.agenticai.core.agents.tools.StepJournal;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L1: when the step journal (and only then) lets an orphan that already started a turn be
 * re-queued.
 */
class ZeroWorkOrphanRecoveryPolicyJournalTest {

    private AgentJobProperties properties;
    private ToolCallLogRepository toolCallLogRepository;
    private StepJournal journal;
    private ZeroWorkOrphanRecoveryPolicy policy;

    @BeforeEach
    void setUp() {
        properties = DurabilityTestSupport.properties();
        toolCallLogRepository = mock(ToolCallLogRepository.class);
        journal = mock(StepJournal.class);
        when(journal.isEnabled()).thenReturn(true);
        when(journal.isDurableCallTrackingActive()).thenReturn(true);
        policy =
                new ZeroWorkOrphanRecoveryPolicy(
                        properties, toolCallLogRepository, new SimpleMeterRegistry());
        policy.setStepJournal(journal);
    }

    private AgentJob startedTurnOrphan() {
        AgentJob orphan = DurabilityTestSupport.abandonedRunningJob();
        orphan.incrementTurn();
        return orphan;
    }

    private void journalTurn(AgentJob orphan, String status, String result) {
        when(journal.find(StepJournal.runIdForJob(orphan.getId()), StepJournal.turnStepId(0)))
                .thenReturn(Optional.of(new StepJournal.Step(status, result)));
    }

    private void enableResume(boolean rerunIncomplete) {
        properties.getDurability().setStepJournalEnabled(true);
        properties.getDurability().setResumeFromJournal(true);
        properties.getDurability().setResumeRerunIncompleteTurn(rerunIncomplete);
    }

    @Test
    void flagsOffMeansAStartedTurnIsStillFailed() {
        AgentJob orphan = startedTurnOrphan();
        journalTurn(orphan, StepJournal.COMPLETED, "r");
        // resume-from-journal left at its default (false)
        assertEquals(Verdict.FAIL, policy.decide(orphan));
        verify(journal, never()).find(anyString(), anyString());
    }

    @Test
    void aCompletedLastTurnIsRequeuedWithoutAToolCallLookup() {
        enableResume(false);
        AgentJob orphan = startedTurnOrphan();
        journalTurn(orphan, StepJournal.COMPLETED, "result");

        assertEquals(Verdict.REQUEUE, policy.decide(orphan));
        // Logged tool calls are expected for a completed turn; the zero-work query is not the
        // argument here.
        verify(toolCallLogRepository, never())
                .countByAgentIdAndSessionId(any(UUID.class), anyString());
    }

    @Test
    void anIncompleteLastTurnIsFailedUnlessRerunIsOptedInto() {
        enableResume(false);
        AgentJob orphan = startedTurnOrphan();
        journalTurn(orphan, StepJournal.STARTED, null);
        assertEquals(Verdict.FAIL, policy.decide(orphan));

        enableResume(true);
        assertEquals(Verdict.REQUEUE, policy.decide(orphan));
    }

    @Test
    void noJournalRowIsNotAResumePoint() {
        enableResume(true);
        AgentJob orphan = startedTurnOrphan();
        when(journal.find(anyString(), anyString())).thenReturn(Optional.empty());
        assertEquals(Verdict.FAIL, policy.decide(orphan));
    }

    @Test
    void attemptCeilingAndCancelStillWinOverAJournalResume() {
        enableResume(true);
        AgentJob exhausted = startedTurnOrphan();
        journalTurn(exhausted, StepJournal.COMPLETED, "r");
        properties.getDurability().setMaxAttempts(1);
        assertEquals(Verdict.FAIL, policy.decide(exhausted));

        properties.getDurability().setMaxAttempts(2);
        AgentJob cancelled = startedTurnOrphan();
        journalTurn(cancelled, StepJournal.COMPLETED, "r");
        cancelled.markCancelRequested("bob@example.com", java.time.Instant.now());
        assertEquals(Verdict.FAIL, policy.decide(cancelled));
    }

    @Test
    void aStartedTurnWithADurableCallInFlightIsNeverRerunAndNeedsReview() {
        enableResume(true);
        AgentJob orphan = startedTurnOrphan();
        journalTurn(orphan, StepJournal.STARTED, null);
        when(journal.hasIncompleteDurableCall(orphan.getId())).thenReturn(true);

        assertEquals(Verdict.FAIL, policy.decide(orphan));

        org.junit.jupiter.api.Assertions.assertNotNull(policy.takeNeedsReviewReason(orphan));
        org.junit.jupiter.api.Assertions.assertNull(
                policy.takeNeedsReviewReason(orphan), "consumed once");
    }

    @Test
    void aStartedTurnWithNoDurableCallInFlightIsStillRerunWhenRerunIsOn() {
        enableResume(true);
        AgentJob orphan = startedTurnOrphan();
        journalTurn(orphan, StepJournal.STARTED, null);
        when(journal.hasIncompleteDurableCall(orphan.getId())).thenReturn(false);

        assertEquals(Verdict.REQUEUE, policy.decide(orphan));
        org.junit.jupiter.api.Assertions.assertNull(policy.takeNeedsReviewReason(orphan));
    }

    @Test
    void rerunIsRefusedWithNeedsReviewWhenTheEffectJournalIsOff() {
        enableResume(true);
        AgentJob orphan = startedTurnOrphan();
        journalTurn(orphan, StepJournal.STARTED, null);
        when(journal.isDurableCallTrackingActive()).thenReturn(false);
        when(journal.hasIncompleteDurableCall(orphan.getId())).thenReturn(false);

        assertEquals(Verdict.FAIL, policy.decide(orphan));
        org.junit.jupiter.api.Assertions.assertNotNull(policy.takeNeedsReviewReason(orphan));
    }

    @Test
    void aCompletedTurnWithABlankResultIsNotAResumePoint() {
        enableResume(false);
        AgentJob orphan = startedTurnOrphan();
        journalTurn(orphan, StepJournal.COMPLETED, " ");
        assertEquals(
                Verdict.FAIL,
                policy.decide(orphan),
                "no usable result and rerun is off: fail, not an empty success");

        enableResume(true);
        assertEquals(Verdict.REQUEUE, policy.decide(orphan), "falls back to the rerun-safety rule");

        when(journal.hasIncompleteDurableCall(orphan.getId())).thenReturn(true);
        assertEquals(Verdict.FAIL, policy.decide(orphan));
    }

    @Test
    void aDisabledOrAbsentJournalBeanLeavesTodaysBehaviour() {
        enableResume(true);
        AgentJob orphan = startedTurnOrphan();
        journalTurn(orphan, StepJournal.COMPLETED, "r");

        when(journal.isEnabled()).thenReturn(false);
        assertEquals(Verdict.FAIL, policy.decide(orphan));

        policy.setStepJournal(null);
        assertEquals(Verdict.FAIL, policy.decide(orphan));
    }
}
