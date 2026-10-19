package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.PlatformIssueService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * L1 enable-blockers at the journal level: in-flight DURABLE call markers, blank-result steps,
 * retention purge.
 */
class StepJournalDurabilityBlockersTest {

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
        when(repo.existsByRunIdAndKindAndStatus(anyString(), anyString(), anyString()))
                .thenAnswer(
                        inv ->
                                store.values().stream()
                                        .anyMatch(
                                                e ->
                                                        e.getRunId().equals(inv.getArgument(0))
                                                                && e.getKind()
                                                                        .equals(inv.getArgument(1))
                                                                && e.getStatus()
                                                                        .equals(
                                                                                inv.getArgument(
                                                                                        2))));
        return repo;
    }

    @Test
    void aBlankOrNullCompletedResultIsNotAUsableResult() {
        assertFalse(new StepJournal.Step(StepJournal.COMPLETED, null).completedWithResult());
        assertFalse(new StepJournal.Step(StepJournal.COMPLETED, "  ").completedWithResult());
        assertFalse(new StepJournal.Step(StepJournal.STARTED, "x").completedWithResult());
        assertTrue(new StepJournal.Step(StepJournal.COMPLETED, "x").completedWithResult());
    }

    @Test
    void aDurableCallIsInFlightFromBeginUntilComplete() {
        Map<String, StepJournalEntry> store = new HashMap<>();
        StepJournal j = new StepJournal(fakeRepository(store), true, true, Clock.systemUTC());
        UUID jobId = UUID.randomUUID();
        String session = "job:" + jobId;

        assertFalse(j.hasIncompleteDurableCall(jobId));
        j.beginDurableCall(session, "k1");
        assertTrue(j.hasIncompleteDurableCall(jobId));
        j.completeDurableCall(session, "k1");
        assertFalse(j.hasIncompleteDurableCall(jobId));
    }

    @Test
    void nonJobSessionsAndDisabledJournalsNeverWriteInFlightRows() {
        Map<String, StepJournalEntry> store = new HashMap<>();
        StepJournal on = new StepJournal(fakeRepository(store), true, true, Clock.systemUTC());
        on.beginDurableCall("console-1", "k");
        on.beginDurableCall("job:not-a-uuid", "k");
        StepJournal off = new StepJournal(fakeRepository(store), false, true, Clock.systemUTC());
        off.beginDurableCall("job:" + UUID.randomUUID(), "k");
        assertEquals(0, store.size());
        assertFalse(off.hasIncompleteDurableCall(UUID.randomUUID()));
    }

    @Test
    void anUnprovableInFlightLookupCountsAsInFlight() {
        StepJournalRepository repo = mock(StepJournalRepository.class);
        when(repo.existsByRunIdAndKindAndStatus(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("db down"));
        StepJournal j = new StepJournal(repo, true, true, Clock.systemUTC());
        assertTrue(j.hasIncompleteDurableCall(UUID.randomUUID()));
    }

    @Test
    void purgeOlderThanDeletesByCutoffAndIsInertWhenDisabled() {
        StepJournalRepository repo = mock(StepJournalRepository.class);
        Instant cutoff = Instant.now().minus(Duration.ofDays(14));
        when(repo.deleteByCreatedAtBefore(cutoff)).thenReturn(7);

        assertEquals(
                7, new StepJournal(repo, true, true, Clock.systemUTC()).purgeOlderThan(cutoff));
        assertEquals(
                0, new StepJournal(repo, false, true, Clock.systemUTC()).purgeOlderThan(cutoff));
        verify(repo).deleteByCreatedAtBefore(cutoff);
    }

    @Test
    void purgeFailureIsSwallowed() {
        StepJournalRepository repo = mock(StepJournalRepository.class);
        when(repo.deleteByCreatedAtBefore(any())).thenThrow(new IllegalStateException("boom"));
        assertEquals(
                0,
                new StepJournal(repo, true, true, Clock.systemUTC()).purgeOlderThan(Instant.now()));
    }

    @Test
    void aThrowingDurableToolLeavesTheCallInFlightButAReturningOneClearsIt() {
        Map<String, StepJournalEntry> store = new HashMap<>();
        StepJournal durable = new StepJournal(fakeRepository(store), true, true, Clock.systemUTC());
        EffectJournal effects =
                new EffectJournal(true, Duration.ofMinutes(30), 10, null, Clock.systemUTC());
        effects.setDurableStore(durable);
        UUID jobId = UUID.randomUUID();
        String session = "job:" + jobId;

        ToolCallback crashing = tool("send_email");
        when(crashing.call(anyString())).thenThrow(new IllegalStateException("died mid-send"));
        try {
            wrap(crashing, effects, session).call("{\"to\":\"x\"}");
        } catch (IllegalStateException expected) {
            // the tool died; the marker must stay
        }
        assertTrue(durable.hasIncompleteDurableCall(jobId));

        ToolCallback ok = tool("send_email");
        when(ok.call(anyString())).thenReturn("sent #1");
        assertEquals("sent #1", wrap(ok, effects, session).call("{\"to\":\"x\"}"));
        assertFalse(
                durable.hasIncompleteDurableCall(jobId),
                "a completed retry of the same call clears the marker");
        verify(ok).call(anyString());
    }

    @Test
    void durableCallsOutsideDurableJobScopeWriteNoMarker() {
        Map<String, StepJournalEntry> store = new HashMap<>();
        StepJournal observeOnly =
                new StepJournal(fakeRepository(store), true, false, Clock.systemUTC());
        EffectJournal effects =
                new EffectJournal(true, Duration.ofMinutes(30), 10, null, Clock.systemUTC());
        effects.setDurableStore(observeOnly);
        ToolCallback ok = tool("send_email");
        when(ok.call(anyString())).thenReturn("sent");
        wrap(ok, effects, "job:" + UUID.randomUUID()).call("{}");
        assertEquals(0, store.size());
        verify(ok).call(anyString());
    }

    @Test
    void aRepeatCallAfterACompletedMarkIsInFlightAgain() {
        Map<String, StepJournalEntry> store = new HashMap<>();
        StepJournal j = new StepJournal(fakeRepository(store), true, true, Clock.systemUTC());
        UUID jobId = UUID.randomUUID();
        String session = "job:" + jobId;
        j.beginDurableCall(session, "k1");
        j.completeDurableCall(session, "k1");
        assertFalse(j.hasIncompleteDurableCall(jobId));

        j.beginDurableCall(session, "k1");
        assertTrue(
                j.hasIncompleteDurableCall(jobId),
                "a crash during the repeat call must be visible");
    }

    @Test
    void durableCallTrackingNeedsTheEffectJournalFlag() {
        StepJournal j =
                new StepJournal(fakeRepository(new HashMap<>()), true, true, Clock.systemUTC());
        assertFalse(j.isDurableCallTrackingActive());
        j.setEffectJournalEnabled(true);
        assertTrue(j.isDurableCallTrackingActive());
        StepJournal noResume =
                new StepJournal(fakeRepository(new HashMap<>()), true, false, Clock.systemUTC());
        noResume.setEffectJournalEnabled(true);
        assertFalse(noResume.isDurableCallTrackingActive());
    }

    private static ToolCallback tool(String name) {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name(name)
                                .description("d")
                                .inputSchema("{}")
                                .build());
        return delegate;
    }

    private static LoggingToolCallback wrap(
            ToolCallback delegate, EffectJournal journal, String session) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformIssueService> issues = mock(ObjectProvider.class);
        return new LoggingToolCallback(
                delegate,
                mock(ToolCallLogRepository.class),
                UUID.randomUUID(),
                session,
                issues,
                mock(ToolCircuitBreakerService.class),
                null,
                journal,
                name -> true,
                "turn-A",
                new SimpleMeterRegistry());
    }
}
