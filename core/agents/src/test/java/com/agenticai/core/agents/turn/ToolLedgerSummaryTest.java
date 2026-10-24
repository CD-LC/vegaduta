package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What goes into the tool ledger: summarised to the configured limits and masked like the tool-call log and
 * the model-facing tool result are, because the ledger is stored and replayed to the model.
 */
class ToolLedgerSummaryTest {

    private final ToolLedgerProperties limits = new ToolLedgerProperties();

    @Test
    void anEntryIsOneCompactLineWithTheToolStatusAndApproval() {
        ToolLedger.Entry e =
                ToolLedgerSummary.entry(
                        "create_file",
                        "{\n  \"path\": \"/work/report.md\",\n  \"body\": \"hi\"\n}",
                        "{\"fileId\":\"file-42\"}",
                        ToolLedger.Status.OK,
                        null,
                        limits,
                        null);

        assertEquals("create_file", e.tool());
        assertEquals("{ \"path\": \"/work/report.md\", \"body\": \"hi\" }", e.arguments());
        assertEquals("{\"fileId\":\"file-42\"}", e.result());
        assertEquals(ToolLedger.Status.OK, e.status());
        assertNull(e.approval());
    }

    @Test
    void argumentsAndResultsAreCutToTheirOwnLimitsWithTheConfiguredMarker() {
        limits.setMaxArgumentChars(10);
        limits.setMaxResultChars(20);
        limits.setCutMarker("[cut]");

        ToolLedger.Entry e =
                ToolLedgerSummary.entry("t", "a".repeat(50), "b".repeat(50), ToolLedger.Status.OK, null, limits, null);

        assertEquals("a".repeat(10) + "[cut]", e.arguments());
        assertEquals("b".repeat(20) + "[cut]", e.result());
    }

    @Test
    void credentialShapedTextIsMaskedEvenWhenItStraddlesTheCut() {
        String secret = "sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";
        limits.setMaxResultChars(40);
        String result = "token is " + secret + " and more text follows";

        ToolLedger.Entry e = ToolLedgerSummary.entry("t", "{}", result, ToolLedger.Status.OK, null, limits, null);

        assertFalse(e.result().contains("AbCdEf"), "no part of the secret survives: " + e.result());
        assertFalse(e.result().contains("sk-ant"), e.result());
    }

    @Test
    void theTenantRedactorForModelFacingTextIsAppliedToArgumentsAndResults() {
        ToolLedger.Entry e =
                ToolLedgerSummary.entry(
                        "lookup",
                        "{\"email\":\"jane@example.test\"}",
                        "Jane Doe, jane@example.test",
                        ToolLedger.Status.OK,
                        null,
                        limits,
                        text -> text.replace("jane@example.test", "[email]"));

        assertEquals("{\"email\":\"[email]\"}", e.arguments());
        assertEquals("Jane Doe, [email]", e.result());
    }

    @Test
    void aRedactorThatThrowsDoesNotLoseTheEntryAndTheCredentialScrubStillRuns() {
        ToolLedger.Entry e =
                ToolLedgerSummary.entry(
                        "t",
                        "{}",
                        "Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123456789",
                        ToolLedger.Status.OK,
                        null,
                        limits,
                        text -> {
                            throw new IllegalStateException("policy lookup failed");
                        });

        assertFalse(e.result().contains("abcdefghijklmnopqrstuvwxyz"), e.result());
    }

    @Test
    void theLimitsCannotBeSetAboveTheCredentialScrubsOwnCap() {
        limits.setMaxResultChars(100_000);
        limits.setMaxArgumentChars(100_000);

        assertEquals(ToolLedgerSummary.MAX_FIELD_CHARS, limits.getMaxResultChars());
        assertEquals(ToolLedgerSummary.MAX_FIELD_CHARS, limits.getMaxArgumentChars());
    }

    @Test
    void aMissingToolNameOrTextIsHandledNotThrown() {
        ToolLedger.Entry e = ToolLedgerSummary.entry(null, null, null, ToolLedger.Status.NOT_RUN, "  ", limits, null);

        assertEquals("?", e.tool());
        assertEquals("", e.arguments());
        assertEquals("", e.result());
        assertNull(e.approval(), "a blank approval is no approval");
    }

    @Test
    void theCollectorListsUpToTheLimitAndCountsTheRest() {
        TurnToolLedger collector = new TurnToolLedger();
        for (int i = 0; i < 5; i++) {
            collector.record(
                    ToolLedgerSummary.entry("t" + i, "{}", "r", ToolLedger.Status.OK, null, limits, null), 3);
        }

        ToolLedger ledger = collector.snapshot();
        assertEquals(3, ledger.entries().size());
        assertEquals(2, ledger.omitted());
        assertEquals("t0", ledger.entries().get(0).tool(), "the first calls are listed, in order");
        assertFalse(ledger.none());
    }

    @Test
    void anEmptyCollectorHasNothingToStore() {
        TurnToolLedger collector = new TurnToolLedger();

        assertTrue(collector.snapshot().none());
        assertNull(collector.snapshotOrNull());
        assertNull(ToolLedger.nullIfNone(new ToolLedger(null, 0)));
    }
}
