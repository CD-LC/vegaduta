package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.ToolLedger;
import com.agenticai.core.agents.turn.ToolLedgerProperties;
import com.agenticai.core.agents.turn.TurnToolActivity;
import com.agenticai.core.guardrails.ApprovalStateFixture;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link LoggingToolCallback} is the single place every tool call of a turn passes through, so it is where
 * the turn's tool ledger is written: each call becomes one summarised, masked entry with the status the
 * model needs (ran, failed, did not run, waiting for a human, repeat).
 */
class LoggingToolCallbackLedgerTest {

    private final TurnToolActivity activity = new TurnToolActivity();
    private final ToolLedgerProperties limits = new ToolLedgerProperties();

    private static ToolCallback tool(String name, Function<String, String> body) {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition())
                .thenReturn(ToolDefinition.builder().name(name).description("d").inputSchema("{}").build());
        when(delegate.call(any(String.class))).thenAnswer(inv -> body.apply(inv.getArgument(0)));
        return delegate;
    }

    private LoggingToolCallback wrap(ToolCallback delegate, ToolCircuitBreakerService breaker, EffectJournal journal) {
        return new LoggingToolCallback(
                        delegate,
                        mock(ToolCallLogRepository.class),
                        UUID.randomUUID(),
                        "session-1",
                        mock(ObjectProvider.class),
                        breaker,
                        null,
                        journal,
                        journal == null ? null : name -> true,
                        "turn-1",
                        new SimpleMeterRegistry())
                .withActivity(activity);
    }

    private LoggingToolCallback wrap(ToolCallback delegate) {
        return wrap(delegate, mock(ToolCircuitBreakerService.class), null)
                .withLedger(limits, null, null);
    }

    private ToolLedger.Entry onlyEntry() {
        ToolLedger ledger = activity.ledger().snapshot();
        assertEquals(1, ledger.entries().size(), ledger.toString());
        return ledger.entries().get(0);
    }

    @Test
    void aCallThatRanIsRecordedWithItsArgumentsAndResult() {
        wrap(tool("get_order", args -> "{\"id\":7,\"total\":12.5}")).call("{\"orderId\":7}");

        ToolLedger.Entry e = onlyEntry();
        assertEquals("get_order", e.tool());
        assertEquals("{\"orderId\":7}", e.arguments());
        assertEquals("{\"id\":7,\"total\":12.5}", e.result());
        assertEquals(ToolLedger.Status.OK, e.status());
    }

    @Test
    void aToolThatThrowsIsRecordedAsFailedWithTheScrubbedMessageAndStillThrows() {
        LoggingToolCallback logged =
                wrap(tool("send_email", args -> {
                    throw new IllegalStateException("smtp rejected: Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123456789");
                }));

        assertThrows(IllegalStateException.class, () -> logged.call("{}"));

        ToolLedger.Entry e = onlyEntry();
        assertEquals(ToolLedger.Status.FAILED, e.status());
        assertTrue(e.result().contains("smtp rejected"), e.result());
        assertFalse(e.result().contains("abcdefghijklmnopqrstuvwxyz"), "credentials never reach the ledger: " + e.result());
    }

    @Test
    void aCallAGuardAnsweredInsteadIsRecordedAsNotRun() {
        wrap(tool("delete_all", args -> "This tool call (delete_all) needs approval before it can run.")).call("{}");

        assertEquals(ToolLedger.Status.NOT_RUN, onlyEntry().status());
    }

    @Test
    void aStepBudgetStopIsRecordedAsNotRunBecauseThePlatformRaisedItNotTheTool() {
        LoggingToolCallback logged =
                wrap(tool("get_order", args -> {
                    throw new ToolStepLimitExceededException(UUID.randomUUID(), "session-1", 15);
                }));

        assertThrows(ToolStepLimitExceededException.class, () -> logged.call("{}"));

        assertEquals(ToolLedger.Status.NOT_RUN, onlyEntry().status());
    }

    @Test
    void aCircuitBlockedCallIsRecordedAsNotRunWithTheBlockMessage() {
        ToolCircuitBreakerService breaker = mock(ToolCircuitBreakerService.class);
        when(breaker.openInfo("flaky")).thenReturn(new ToolCircuitBreakerService.OpenInfo(120, "timeout"));
        LoggingToolCallback logged = wrap(tool("flaky", args -> "never"), breaker, null).withLedger(limits, null, null);

        logged.call("{}");

        ToolLedger.Entry e = onlyEntry();
        assertEquals(ToolLedger.Status.NOT_RUN, e.status());
        assertTrue(e.result().contains("temporarily disabled"), e.result());
    }

    @Test
    void aCallHeldForAHumanIsRecordedWithTheRequestIdAndNoResultText() {
        LoggingToolCallback logged =
                wrap(tool("send_invoice", args -> "This action ('send_invoice') requires human approval before it can run."), mock(ToolCircuitBreakerService.class), null)
                        .withLedger(limits, ApprovalStateFixture.heldCall("send_invoice", "{\"to\":\"a\"}", "req-77"), null);

        logged.call("{\"to\":\"a\"}");

        ToolLedger.Entry e = onlyEntry();
        assertEquals(ToolLedger.Status.AWAITING_APPROVAL, e.status());
        assertEquals("req-77", e.approval());
        assertEquals("", e.result(), "the park text belongs to the platform; the status says it");
    }

    @Test
    void aDifferentCallInTheSameTurnIsNotMistakenForTheHeldOne() {
        LoggingToolCallback logged =
                wrap(tool("send_invoice", args -> "{\"sent\":true}"), mock(ToolCircuitBreakerService.class), null)
                        .withLedger(limits, ApprovalStateFixture.heldCall("send_invoice", "{\"to\":\"a\"}", "req-77"), null);

        logged.call("{\"to\":\"someone else\"}");

        assertEquals(ToolLedger.Status.OK, onlyEntry().status());
    }

    @Test
    void aRepeatServedFromTheEffectJournalIsRecordedAsReplayed() {
        EffectJournal journal = new EffectJournal(true, Duration.ofMinutes(30), 10, null, Clock.systemUTC());
        LoggingToolCallback logged =
                wrap(tool("send_email", args -> "{\"sent\":true}"), mock(ToolCircuitBreakerService.class), journal)
                        .withLedger(limits, null, null);

        logged.call("{\"to\":\"a@b.c\"}");
        logged.call("{\"to\":\"a@b.c\"}");

        var entries = activity.ledger().snapshot().entries();
        assertEquals(ToolLedger.Status.OK, entries.get(0).status());
        assertEquals(ToolLedger.Status.REPLAYED, entries.get(1).status());
    }

    @Test
    void thePersonalDataRedactorOfTheModelFacingResultIsAppliedToTheRecord() {
        UnaryOperator<String> mask = text -> text.replace("jane@example.test", "[email]");
        LoggingToolCallback logged =
                wrap(tool("lookup", args -> "{\"email\":\"jane@example.test\"}"), mock(ToolCircuitBreakerService.class), null)
                        .withLedger(limits, null, mask);

        logged.call("{\"q\":\"jane@example.test\"}");

        ToolLedger.Entry e = onlyEntry();
        assertEquals("{\"email\":\"[email]\"}", e.result());
        assertEquals("{\"q\":\"[email]\"}", e.arguments());
    }

    @Test
    void callsPastTheEntryLimitAreCountedNotListed() {
        limits.setMaxEntriesPerTurn(2);
        LoggingToolCallback logged = wrap(tool("t", args -> "r"));

        for (int i = 0; i < 5; i++) {
            logged.call("{\"i\":" + i + "}");
        }

        ToolLedger ledger = activity.ledger().snapshot();
        assertEquals(2, ledger.entries().size());
        assertEquals(3, ledger.omitted());
    }

    @Test
    void withTheLedgerOffNothingIsRecordedAndTheCallIsUnchanged() {
        limits.setEnabled(false);
        LoggingToolCallback logged = wrap(tool("t", args -> "result"));

        assertEquals("result", logged.call("{}"));

        assertTrue(activity.ledger().snapshot().none());
        assertNull(activity.ledger().snapshotOrNull());
    }

    @Test
    void aWrapperWithoutALedgerConfiguredRecordsNothing() {
        LoggingToolCallback logged = wrap(tool("t", args -> "result"), mock(ToolCircuitBreakerService.class), null);

        logged.call("{}");

        assertTrue(activity.ledger().snapshot().none());
    }
}
