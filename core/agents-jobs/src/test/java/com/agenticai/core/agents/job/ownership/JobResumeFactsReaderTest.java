package com.agenticai.core.agents.job.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.ownership.JobLeaseStore.LeasedJob;
import com.agenticai.core.agents.job.ownership.JobResumePlanner.Facts;
import com.agenticai.core.agents.job.ownership.JobResumePlanner.TurnState;
import com.agenticai.core.agents.tools.StepJournal;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class JobResumeFactsReaderTest {

    private StepJournal journal;
    private ToolCallLogRepository toolLog;
    private AgentJobProperties properties;
    private JobResumeFactsReader reader;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        journal = mock(StepJournal.class);
        toolLog = mock(ToolCallLogRepository.class);
        properties = new AgentJobProperties();
        properties.getDurability().setStepJournalEnabled(true);
        properties.getDurability().setResumeFromJournal(true);
        ObjectProvider<StepJournal> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(journal);
        when(journal.isEnabled()).thenReturn(true);
        reader = new JobResumeFactsReader(provider, toolLog, properties);
    }

    private static LeasedJob job(int turnsUsed) {
        return new LeasedJob(
                UUID.randomUUID(),
                "t",
                UUID.randomUUID(),
                "job:x",
                "dead",
                Instant.EPOCH,
                1,
                turnsUsed,
                false,
                Instant.now());
    }

    @Test
    void readsACompletedTurnWithItsResult() {
        LeasedJob job = job(1);
        when(journal.find(StepJournal.runIdForJob(job.id()), StepJournal.turnStepId(0)))
                .thenReturn(Optional.of(new StepJournal.Step(StepJournal.COMPLETED, "answer")));
        when(journal.isDurableCallTrackingActive()).thenReturn(true);
        when(journal.hasIncompleteDurableCall(job.id())).thenReturn(false);
        when(toolLog.countByAgentIdAndSessionId(any(), any())).thenReturn(3L);

        Facts f = reader.read(job);

        assertThat(f.journalUsable()).isTrue();
        assertThat(f.lastTurn()).isEqualTo(TurnState.COMPLETED_WITH_RESULT);
        assertThat(f.durableTrackingActive()).isTrue();
        assertThat(f.incompleteDurableCall()).isFalse();
        assertThat(f.loggedToolCalls()).isEqualTo(3L);
    }

    @Test
    void distinguishesStartedFromACompletedButBlankTurn() {
        LeasedJob job = job(1);
        when(journal.find(StepJournal.runIdForJob(job.id()), StepJournal.turnStepId(0)))
                .thenReturn(Optional.of(new StepJournal.Step(StepJournal.STARTED, null)));
        assertThat(reader.read(job).lastTurn()).isEqualTo(TurnState.STARTED);

        when(journal.find(StepJournal.runIdForJob(job.id()), StepJournal.turnStepId(0)))
                .thenReturn(Optional.of(new StepJournal.Step(StepJournal.COMPLETED, "  ")));
        assertThat(reader.read(job).lastTurn()).isEqualTo(TurnState.COMPLETED_BLANK);
    }

    @Test
    void aMissingJournalRowIsNoRecordNeverAResumePoint() {
        LeasedJob job = job(1);
        when(journal.find(any(), any())).thenReturn(Optional.empty());
        assertThat(reader.read(job).lastTurn()).isEqualTo(TurnState.NONE);
    }

    @Test
    void anInFlightDurableCallIsReported() {
        LeasedJob job = job(1);
        when(journal.find(any(), any()))
                .thenReturn(Optional.of(new StepJournal.Step(StepJournal.STARTED, null)));
        when(journal.isDurableCallTrackingActive()).thenReturn(true);
        when(journal.hasIncompleteDurableCall(job.id())).thenReturn(true);
        assertThat(reader.read(job).incompleteDurableCall()).isTrue();
    }

    @Test
    void theJournalIsNotUsableUnlessBothFlagsAreOn() {
        properties.getDurability().setResumeFromJournal(false);
        Facts f = reader.read(job(1));
        assertThat(f.journalUsable()).isFalse();
        assertThat(f.lastTurn()).isEqualTo(TurnState.NONE);
    }

    @Test
    void aFailedToolLogLookupIsReportedAsUnknownNotZero() {
        when(toolLog.countByAgentIdAndSessionId(any(), any()))
                .thenThrow(new IllegalStateException("db"));
        assertThat(reader.read(job(0)).loggedToolCalls()).isNull();
    }

    @Test
    void aZeroTurnJobReadsNoJournalAtAll() {
        when(toolLog.countByAgentIdAndSessionId(any(), any())).thenReturn(0L);
        Facts f = reader.read(job(0));
        assertThat(f.lastTurn()).isEqualTo(TurnState.NONE);
        assertThat(f.loggedToolCalls()).isZero();
    }
}
