package com.agenticai.core.agents.tools;

import com.agenticai.core.guardrails.ToolResultMarker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.TurnToolActivity;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link LoggingToolCallback} is the one place every tool call of a turn passes through, so it is
 * where the turn's {@link TurnToolActivity} is fed: a call is in flight while the tool works (the
 * stream idle timer reads that), and it ends as executed, journaled, failed, replayed or not-run (a
 * failover reads that to decide whether replaying the turn could act a second time).
 */
class LoggingToolCallbackActivityTest {

    private final TurnToolActivity activity = new TurnToolActivity();

    private static ToolCallback tool(String name, Function<String, String> body) {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition())
                .thenReturn(ToolDefinition.builder().name(name).description("d").inputSchema("{}").build());
        when(delegate.call(any(String.class))).thenAnswer(inv -> body.apply(inv.getArgument(0)));
        return delegate;
    }

    @SuppressWarnings("unchecked")
    private LoggingToolCallback wrap(ToolCallback delegate, EffectJournal journal, boolean durable) {
        ToolCircuitBreakerService breaker = mock(ToolCircuitBreakerService.class);
        return new LoggingToolCallback(
                        delegate,
                        mock(ToolCallLogRepository.class),
                        UUID.randomUUID(),
                        "session-1",
                        mock(ObjectProvider.class),
                        breaker,
                        null,
                        journal,
                        journal == null ? null : name -> durable,
                        "turn-1",
                        new SimpleMeterRegistry())
                .withActivity(activity);
    }

    private static EffectJournal journal() {
        return new EffectJournal(true, Duration.ofMinutes(30), 10, null, Clock.systemUTC());
    }

    @Test
    void aCallIsInFlightWhileTheToolRunsAndFinishesAsExecuted() {
        AtomicInteger inFlightInsideTheTool = new AtomicInteger(-1);
        AtomicReference<String> runningTool = new AtomicReference<>();
        LoggingToolCallback logged = wrap(tool("get_order", args -> {
            inFlightInsideTheTool.set(activity.inFlightCount());
            runningTool.set(activity.snapshot().inFlight().get(0));
            return "{\"id\":7}";
        }), null, false);

        logged.call("{}");

        assertEquals(1, inFlightInsideTheTool.get(), "the idle timer must see a tool working");
        assertEquals("get_order", runningTool.get());
        assertEquals(0, activity.inFlightCount());
        assertEquals(1, activity.snapshot().finished().get("get_order").executed());
    }

    @Test
    void aDurableToolTheJournalRecordedFinishesAsJournaledAndItsRepeatAsReplayed() {
        EffectJournal journal = journal();
        AtomicInteger sent = new AtomicInteger();
        LoggingToolCallback logged = wrap(tool("send_email", args -> {
            sent.incrementAndGet();
            return "{\"sent\":true}";
        }), journal, true);

        logged.call("{\"to\":\"a@b.c\"}");
        logged.call("{\"to\":\"a@b.c\"}");

        TurnToolActivity.Tally tally = activity.snapshot().finished().get("send_email");
        assertEquals(1, sent.get(), "the second identical call never reached the provider");
        assertEquals(1, tally.journaled());
        assertEquals(1, tally.replayed());
        assertEquals(0, tally.unjournaled(), "its effect is on record, so a repeat is served from the journal");
    }

    @Test
    void aDurableToolWithNoJournalIsExecutedButNotProtected() {
        LoggingToolCallback logged = wrap(tool("send_email", args -> "{\"sent\":true}"), null, true);

        logged.call("{}");

        TurnToolActivity.Tally tally = activity.snapshot().finished().get("send_email");
        assertEquals(1, tally.executed());
        assertEquals(1, tally.unjournaled());
    }

    @Test
    void aToolThatThrowsFinishesAsFailedBecauseItMayHaveActedFirst() {
        LoggingToolCallback logged = wrap(tool("send_email", args -> {
            throw new IllegalStateException("connection reset after the provider accepted it");
        }), journal(), true);

        assertThrows(IllegalStateException.class, () -> logged.call("{}"));

        TurnToolActivity.Tally tally = activity.snapshot().finished().get("send_email");
        assertEquals(1, tally.failed());
        assertEquals(1, tally.unjournaled());
        assertEquals(0, activity.inFlightCount(), "a throw must not leave the call in flight");
    }

    @Test
    void aCallAGuardAnsweredInsteadIsNotRunAndLeavesNothingBehind() {
        LoggingToolCallback refused = wrap(
                tool("delete_all", args -> ToolResultMarker.mark(
                        "This tool call (delete_all) needs approval before it can run." + System.nanoTime(),
                        ToolResultMarker.Kind.BLOCKED)),
                journal(),
                true);
        LoggingToolCallback overBudget = wrap(tool("get_order", args -> {
            throw new ToolStepLimitExceededException(UUID.randomUUID(), "session-1", 15);
        }), null, false);

        refused.call("{}");
        assertThrows(ToolStepLimitExceededException.class, () -> overBudget.call("{}"));

        assertEquals(1, activity.snapshot().finished().get("delete_all").notRun());
        assertEquals(1, activity.snapshot().finished().get("get_order").notRun());
        assertFalse(activity.anyToolRan());
        assertTrue(activity.snapshot().finished().get("delete_all").reachedTool() == 0);
    }

    @Test
    void aCallWithNoActivityAttachedBehavesAsBefore() {
        ToolCallback delegate = tool("get_order", args -> "ok");
        @SuppressWarnings("unchecked")
        LoggingToolCallback plain = new LoggingToolCallback(
                delegate,
                mock(ToolCallLogRepository.class),
                UUID.randomUUID(),
                "session-1",
                mock(ObjectProvider.class),
                mock(ToolCircuitBreakerService.class),
                null);

        assertEquals("ok", plain.call("{}"));
    }
}
