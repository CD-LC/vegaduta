package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.ToolLedger;
import com.agenticai.core.agents.turn.ToolLedgerProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The session history keeps each assistant turn's tool ledger and replays it: the model's window is
 * measured on the text it will be sent (record included), the transcript reads are untouched, the rolling
 * summary of turns that leave the window sees the record, and SEC-004 scoping is not involved at all (the
 * ledger rides inside the same stored entry, under the same scoped key).
 */
class SessionMemoryToolLedgerTest extends SessionMemoryServiceTestBase {

    private static ToolLedger ledgerOf(String tool, String result) {
        return new ToolLedger(List.of(new ToolLedger.Entry(tool, "{}", result, ToolLedger.Status.OK, null)), 0);
    }

    private ChatTurn assistantWithTools(String content, ToolLedger ledger) {
        return assistant(content).withTools(ledger);
    }

    @Test
    void theWindowReplaysTheRecordAheadOfTheReplyAndFullHistoryKeepsTheTurnAsStored() {
        SessionMemoryService service = newService(40, 32000, 0);
        ChatTurn withTools = assistantWithTools("I created it.", ledgerOf("create_file", "{\"fileId\":\"f-1\"}"));
        List<ChatTurn> stored = List.of(user("make a file"), withTools);
        stubTailRange(40, stored);
        when(listOps.range(REDIS_KEY, 0, -1)).thenReturn(stored.stream().map(this::json).toList());

        List<ChatTurn> window = service.getHistory(SESSION_ID);
        List<ChatTurn> transcript = service.getFullHistory(SESSION_ID);

        assertEquals(2, window.size());
        assertTrue(window.get(1).content().contains("create_file"), window.get(1).content());
        assertTrue(window.get(1).content().contains("f-1"));
        assertTrue(window.get(1).content().endsWith("I created it."));
        assertEquals("I created it.", transcript.get(1).content(), "the transcript is what the person saw");
        assertNotNull(transcript.get(1).tools());
    }

    @Test
    void theCharacterBudgetIsMeasuredOnWhatTheModelWillBeSent() {
        ToolLedgerProperties props = new ToolLedgerProperties();
        String block = ToolLedgerReplay.render(ledgerOf("create_file", "r".repeat(150)), props);
        int budget = ("old user question".length() + "old reply".length() + 5 + "new".length() + "reply".length());
        SessionMemoryService service = newService(40, budget, 0);
        List<ChatTurn> stored =
                List.of(
                        user("old user question"),
                        assistantWithTools("old reply", ledgerOf("create_file", "r".repeat(150))),
                        user("new"),
                        assistant("reply"));
        stubTailRange(40, stored);

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertTrue(block.length() > budget, "the record alone is bigger than the whole budget");
        assertEquals(
                List.of("new", "reply"),
                window.stream().map(ChatTurn::content).toList(),
                "the older exchange does not fit once its record counts; it is not truncated into the prompt");
    }

    @Test
    void theNewestTurnIsAlwaysReplayedEvenIfItsRecordAloneExceedsTheBudget() {
        SessionMemoryService service = newService(40, 50, 0);
        List<ChatTurn> stored = List.of(user("go"), assistantWithTools("done", ledgerOf("t", "r".repeat(100))));
        stubTailRange(40, stored);

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals(1, window.size(), "the orphaned-assistant rule still applies to a window of one");
        assertTrue(window.get(0).content().contains("done"));
    }

    @Test
    void withTheLedgerOffTheWindowIsTheStoredTextOnly() {
        SessionMemoryService service = newService(40, 32000, 0);
        ToolLedgerProperties off = new ToolLedgerProperties();
        off.setEnabled(false);
        service.setToolLedgerProperties(off);
        List<ChatTurn> stored = List.of(user("go"), assistantWithTools("done", ledgerOf("t", "r")));
        stubTailRange(40, stored);

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals("done", window.get(1).content());
        assertNull(window.get(1).tools());
    }

    @Test
    void turnsLeavingTheWindowReachTheSummariserWithTheirRecordFoldedIn() {
        SessionMemoryService service = newService(40, 32000, 0);
        withSummariserAvailable();
        withTenantContext();
        List<String> batch = new java.util.ArrayList<>(storedJson(38));
        batch.add(json(user("make the report")));
        batch.add(json(assistantWithTools("Created.", ledgerOf("create_file", "{\"fileId\":\"f-9\"}"))));
        stubClaim("40", batch);

        service.appendTurn(SESSION_ID, user("the next one"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatTurn>> leaving =
                ArgumentCaptor.forClass((Class<List<ChatTurn>>) (Class<?>) List.class);
        verify(summariser).foldAsync(eq(SESSION_ID), any(), any(), leaving.capture(), any());
        String lastLeaving = leaving.getValue().get(leaving.getValue().size() - 1).content();
        assertTrue(lastLeaving.contains("create_file") && lastLeaving.contains("f-9"), lastLeaving);
        assertFalse(leaving.getValue().get(0).content().contains("Platform tool record"), "turns without tools are unchanged");
    }

    @Test
    void theSummaryPrependedToTheWindowKeepsTheRecordOfTheTurnItIsMergedInto() {
        SessionMemoryService service = newService(40, 32000, 0);
        withSummariserAvailable();
        when(valueOps.getAndExpire(SUMMARY_KEY, TTL)).thenReturn("they discussed files");
        List<ChatTurn> stored = List.of(assistantWithTools("only turn", ledgerOf("t", "r")));
        stubTailRange(40, stored);

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertTrue(window.get(0).content().startsWith(SessionMemoryService.SUMMARY_PREAMBLE));
        assertNotNull(window.get(0).tools());
    }
}
