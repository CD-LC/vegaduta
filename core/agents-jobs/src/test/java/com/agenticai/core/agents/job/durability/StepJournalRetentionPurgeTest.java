package com.agenticai.core.agents.job.durability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.tools.StepJournal;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Blocker 4: the retention purge deletes by cutoff in every tenant schema, and is inert while the
 * journal is off.
 */
class StepJournalRetentionPurgeTest {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

    private StepJournal journal;
    private AgentJobProperties properties;
    private PgAdvisoryJobLock jobLock;
    private StepJournalRetentionPurge purge;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        journal = mock(StepJournal.class);
        properties = new AgentJobProperties();
        TenantSchemaIterator schemaIterator = mock(TenantSchemaIterator.class);
        doAnswer(
                        inv -> {
                            Consumer<String> action = inv.getArgument(0, Consumer.class);
                            action.accept("tenant_a");
                            action.accept("tenant_b");
                            return null;
                        })
                .when(schemaIterator)
                .forEachActiveTenantSchema(any());
        jobLock = mock(PgAdvisoryJobLock.class);
        when(jobLock.tryRunExclusive(anyString(), any(Runnable.class)))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(1, Runnable.class).run();
                            return true;
                        });
        purge =
                new StepJournalRetentionPurge(
                        journal,
                        properties,
                        schemaIterator,
                        jobLock,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void defaultsToFourteenDays() {
        assertEquals(14, properties.getDurability().getStepJournalRetentionDays());
    }

    @Test
    void purgesEveryTenantSchemaOlderThanTheRetention() {
        properties.getDurability().setStepJournalEnabled(true);
        when(journal.isEnabled()).thenReturn(true);
        when(journal.purgeOlderThan(any())).thenReturn(3);

        purge.purge();

        verify(journal, times(2)).purgeOlderThan(NOW.minus(Duration.ofDays(14)));
    }

    @Test
    void retentionIsFlooredAtOneDay() {
        properties.getDurability().setStepJournalEnabled(true);
        properties.getDurability().setStepJournalRetentionDays(0);
        when(journal.isEnabled()).thenReturn(true);

        purge.purge();

        verify(journal, times(2)).purgeOlderThan(NOW.minus(Duration.ofDays(1)));
    }

    @Test
    void doesNothingWhileTheJournalIsOff() {
        when(journal.isEnabled()).thenReturn(false);
        purge.purge();
        properties.getDurability().setStepJournalEnabled(false);
        when(journal.isEnabled()).thenReturn(true);
        purge.purge();
        verify(journal, never()).purgeOlderThan(any());
        verify(jobLock, never()).tryRunExclusive(anyString(), any(Runnable.class));
    }
}
