package com.agenticai.core.agents.turnjournal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The retention sweep deletes whole old turns in every tenant schema, in bounded batches, and is inert when off. */
class TurnJournalRetentionPurgeTest {

    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

    @SuppressWarnings("unchecked")
    private static TenantSchemaIterator twoTenants() {
        TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
        doAnswer(inv -> {
            Consumer<String> action = inv.getArgument(0, Consumer.class);
            action.accept("tenant_a");
            action.accept("tenant_b");
            return null;
        }).when(iterator).forEachActiveTenantSchema(any());
        return iterator;
    }

    private static PgAdvisoryJobLock runsInline() {
        PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);
        when(lock.tryRunExclusive(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return true;
        });
        return lock;
    }

    private static TurnJournalRetentionPurge purge(TurnJournal journal, boolean enabled, int days, int batch, int max) {
        return new TurnJournalRetentionPurge(
                journal, twoTenants(), runsInline(), Clock.fixed(NOW, ZoneOffset.UTC), () -> enabled, days, batch, max);
    }

    @Test
    void anEnabledJournalIsPurgedAtTheRetentionCutoffInEveryTenantSchema() {
        TurnJournal journal = mock(TurnJournal.class);
        when(journal.purgeOlderThan(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(0);

        purge(journal, true, 30, 500, 5).purge();

        verify(journal, org.mockito.Mockito.times(2)).purgeOlderThan(NOW.minus(Duration.ofDays(30)), 500);
    }

    @Test
    void aSweepKeepsGoingBatchByBatchUntilNothingIsDueButNoFurtherThanTheBatchLimit() {
        TurnJournal journal = mock(TurnJournal.class);
        when(journal.purgeOlderThan(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(10);

        purge(journal, true, 30, 100, 3).purge();

        verify(journal, org.mockito.Mockito.times(6)).purgeOlderThan(any(), org.mockito.ArgumentMatchers.eq(100));
    }

    @Test
    void aJournalThatIsOffOrKeptForEverIsNeverTouched() {
        TurnJournal journal = mock(TurnJournal.class);

        purge(journal, false, 30, 500, 5).purge();
        purge(journal, true, 0, 500, 5).purge();

        verify(journal, never()).purgeOlderThan(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void oneFailingSchemaDoesNotStopTheOthers() {
        TurnJournal journal = mock(TurnJournal.class);
        when(journal.purgeOlderThan(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("tenant_a is down"))
                .thenReturn(0);

        purge(journal, true, 30, 500, 5).purge();

        verify(journal, org.mockito.Mockito.times(2)).purgeOlderThan(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void theJournalDeletesWholeOldTurnsAndKeepsRecentOnes() throws InterruptedException {
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        UUID agent = UUID.randomUUID();
        UUID old = turn(journal, agent);
        Thread.sleep(20);
        Instant cutoff = Instant.now();
        Thread.sleep(20);
        UUID recent = turn(journal, agent);

        int removed = journal.purgeOlderThan(cutoff, 10);

        assertThat(removed).isEqualTo(2);
        assertThat(journal.read(agent, old)).isEmpty();
        List<TurnEvent> kept = journal.read(agent, recent);
        assertThat(kept).hasSize(2);
        assertThat(TurnHashChain.verify(kept).intact()).isTrue();
        assertThat(journal.purgeOlderThan(cutoff, 10)).as("nothing more is due").isZero();
    }

    @Test
    void aTurnWithAnEventPastTheCutoffIsKeptWhole() throws InterruptedException {
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        UUID agent = UUID.randomUUID();
        UUID straddling = UUID.randomUUID();
        journal.append(agent, straddling, TurnEventType.INTAKE, "h".repeat(64), "a");
        Thread.sleep(20);
        Instant cutoff = Instant.now();
        Thread.sleep(20);
        journal.append(agent, straddling, TurnEventType.COMMIT, "h".repeat(64), "status=completed");

        assertThat(journal.purgeOlderThan(cutoff, 10)).isZero();
        assertThat(journal.read(agent, straddling)).hasSize(2);
    }

    private static UUID turn(InMemoryTurnJournal journal, UUID agent) {
        UUID turnId = UUID.randomUUID();
        journal.append(agent, turnId, TurnEventType.INTAKE, "h".repeat(64), "a");
        journal.append(agent, turnId, TurnEventType.COMMIT, "h".repeat(64), "status=completed");
        return turnId;
    }
}
