package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.PlatformIssueService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The stable "job" effect step id in {@link LoggingToolCallback}: a DURABLE write/send in a job
 * session maps to the same key across turns / a resumed attempt ONLY when resume-from-journal is
 * on; with the journal on alone (observability) it keeps the legacy per-turn key.
 */
class LoggingToolCallbackDurableJobScopeTest {

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

    private static EffectJournal journalWith(StepJournal durable) {
        EffectJournal j =
                new EffectJournal(true, Duration.ofMinutes(30), 10, null, Clock.systemUTC());
        j.setDurableStore(durable);
        return j;
    }

    private static LoggingToolCallback wrap(
            ToolCallback delegate, EffectJournal journal, String session, String turn) {
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
                turn,
                new SimpleMeterRegistry());
    }

    private static ToolCallback sender() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("send_email")
                                .description("d")
                                .inputSchema("{}")
                                .build());
        when(delegate.call(anyString())).thenReturn("sent #1");
        return delegate;
    }

    @Test
    void resumeOnSameJobSendIsPerformedOnceAcrossTurnsAndProcesses() {
        Map<String, StepJournalEntry> store = new HashMap<>();
        StepJournal durable = new StepJournal(fakeRepository(store), true, true, Clock.systemUTC());
        ToolCallback delegate = sender();
        String session = "job:" + UUID.randomUUID();

        // Attempt 1 (turn id random per process) performs the send.
        String first =
                wrap(delegate, journalWith(durable), session, "turn-A").call("{\"to\":\"x\"}");
        // Resumed attempt: fresh EffectJournal memory + different turn id -> same stable key ->
        // replayed.
        String second =
                wrap(delegate, journalWith(durable), session, "turn-B").call("{\"to\":\"x\"}");

        assertEquals("sent #1", first);
        assertEquals("sent #1", second);
        verify(delegate, times(1)).call(anyString());
    }

    @Test
    void journalAloneKeepsThePerTurnKeySoNothingIsReplayedAcrossTurns() {
        Map<String, StepJournalEntry> store = new HashMap<>();
        StepJournal observeOnly =
                new StepJournal(fakeRepository(store), true, false, Clock.systemUTC());
        ToolCallback delegate = sender();
        String session = "job:" + UUID.randomUUID();

        wrap(delegate, journalWith(observeOnly), session, "turn-A").call("{\"to\":\"x\"}");
        wrap(delegate, journalWith(observeOnly), session, "turn-B").call("{\"to\":\"x\"}");

        verify(delegate, times(2)).call(anyString());
        assertEquals(0, store.size(), "no durable effect rows without resume-from-journal");
    }
}
