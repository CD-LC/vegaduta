package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The durable run_id+step_id journal and its use as the durable backing of {@link EffectJournal}.
 */
class StepJournalTest {

    /** In-memory stand-in for the JPA repository, keyed by run|step. */
    private static StepJournalRepository fakeRepository(Map<String, StepJournalEntry> store) {
        StepJournalRepository repo = mock(StepJournalRepository.class);
        when(repo.findByRunIdAndStepId(anyString(), anyString()))
                .thenAnswer(
                        inv ->
                                Optional.ofNullable(
                                        store.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(repo.save(any(StepJournalEntry.class)))
                .thenAnswer(
                        inv -> {
                            StepJournalEntry e = inv.getArgument(0);
                            store.put(e.getRunId() + "|" + e.getStepId(), e);
                            return e;
                        });
        return repo;
    }

    @Test
    void beginThenCompleteRoundTripsAResult() {
        Map<String, StepJournalEntry> store = new HashMap<>();
        StepJournal j = new StepJournal(fakeRepository(store), true, Clock.systemUTC());

        j.begin("run-1", "turn:0", StepJournal.KIND_TURN);
        assertFalse(j.find("run-1", "turn:0").orElseThrow().completed());
        assertTrue(j.findCompletedResult("run-1", "turn:0").isEmpty());

        j.complete("run-1", "turn:0", StepJournal.KIND_TURN, "the answer");
        assertEquals("the answer", j.findCompletedResult("run-1", "turn:0").orElseThrow());
        assertEquals(1, store.size());
    }

    @Test
    void beginDoesNotOverwriteACompletedStep() {
        Map<String, StepJournalEntry> store = new HashMap<>();
        StepJournal j = new StepJournal(fakeRepository(store), true, Clock.systemUTC());
        j.complete("r", "s", StepJournal.KIND_SWARM, "done");
        j.begin("r", "s", StepJournal.KIND_SWARM);
        assertEquals("done", j.findCompletedResult("r", "s").orElseThrow());
    }

    @Test
    void disabledJournalTouchesNothing() {
        StepJournalRepository repo = mock(StepJournalRepository.class);
        StepJournal j = new StepJournal(repo, false, Clock.systemUTC());
        j.begin("r", "s", StepJournal.KIND_TURN);
        j.complete("r", "s", StepJournal.KIND_TURN, "x");
        assertTrue(j.find("r", "s").isEmpty());
        assertFalse(j.isActive());
        verify(repo, never()).save(any());
        verify(repo, never()).findByRunIdAndStepId(anyString(), anyString());
    }

    @Test
    void aFailingRepositoryDegradesToNoRecordNeverAnException() {
        StepJournalRepository repo = mock(StepJournalRepository.class);
        when(repo.findByRunIdAndStepId(anyString(), anyString()))
                .thenThrow(new IllegalStateException("db down"));
        StepJournal j = new StepJournal(repo, true, Clock.systemUTC());
        j.begin("r", "s", StepJournal.KIND_TURN);
        j.complete("r", "s", StepJournal.KIND_TURN, "x");
        assertTrue(j.find("r", "s").isEmpty());
        assertTrue(j.findEffect("k").isEmpty());
    }

    @Test
    void effectJournalFallsBackToTheDurableStoreAfterARestart() {
        Map<String, StepJournalEntry> store = new HashMap<>();
        StepJournal durable = new StepJournal(fakeRepository(store), true, Clock.systemUTC());

        // Process 1 performs the write/send and records it.
        EffectJournal before =
                new EffectJournal(true, Duration.ofMinutes(30), 10, null, Clock.systemUTC());
        before.setDurableStore(durable);
        before.record("effect-key", "sent #1");

        // Process 2 (fresh memory) must still see it: exactly-once across a restart.
        EffectJournal after =
                new EffectJournal(true, Duration.ofMinutes(30), 10, null, Clock.systemUTC());
        assertTrue(after.lookup("effect-key").isEmpty());
        after.setDurableStore(durable);
        assertEquals("sent #1", after.lookup("effect-key").orElseThrow());
    }

    @Test
    void purgeJobRunDeletesTurnAndSwarmRowsOfThatJobOnly() {
        StepJournalRepository repo = mock(StepJournalRepository.class);
        StepJournal j = new StepJournal(repo, true, Clock.systemUTC());
        java.util.UUID id = java.util.UUID.randomUUID();

        j.purgeJobRun(id);

        verify(repo).deleteByRunIdIn(java.util.List.of(id.toString(), "job:" + id));
        StepJournalRepository offRepo = mock(StepJournalRepository.class);
        new StepJournal(offRepo, false, Clock.systemUTC()).purgeJobRun(id);
        verify(offRepo, never()).deleteByRunIdIn(any());
    }

    @Test
    void durableJobScopeOnlyForJobSessionsWithAnActiveStore() {
        StepJournal on =
                new StepJournal(mock(StepJournalRepository.class), true, Clock.systemUTC());
        StepJournal off =
                new StepJournal(mock(StepJournalRepository.class), false, Clock.systemUTC());

        EffectJournal j =
                new EffectJournal(true, Duration.ofMinutes(30), 10, null, Clock.systemUTC());
        assertFalse(j.durableJobScope("job:abc"), "no store wired => legacy per-turn key");

        j.setDurableStore(off);
        assertFalse(j.durableJobScope("job:abc"), "store wired but flag off");

        j.setDurableStore(on);
        assertTrue(j.durableJobScope("job:abc"));
        assertFalse(
                j.durableJobScope("console-session-1"),
                "only agent-job sessions get the stable step id");

        // Journal on, resume-from-journal OFF: observability only - no stable key, no durable
        // read/write.
        StepJournal observeOnly =
                new StepJournal(mock(StepJournalRepository.class), true, false, Clock.systemUTC());
        assertTrue(observeOnly.isEnabled());
        assertFalse(observeOnly.isResumeEnabled());
        j.setDurableStore(observeOnly);
        assertFalse(
                j.durableJobScope("job:abc"), "journal alone must not change live tool behaviour");
        assertFalse(j.durableJobScope(null));
    }
}
