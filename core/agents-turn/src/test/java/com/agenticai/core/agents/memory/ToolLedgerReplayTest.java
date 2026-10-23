package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.turn.ToolLedger;
import com.agenticai.core.agents.turn.ToolLedger.Status;
import com.agenticai.core.agents.turn.ToolLedgerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A turn's tool record as the model reads it, and as it round-trips through the JSON the history is stored
 * in. History written before the ledger existed must read, and a turn without tools must write, exactly as
 * before.
 */
class ToolLedgerReplayTest {

    private static final Instant T = Instant.parse("2026-10-01T00:00:00Z");
    private final ToolLedgerProperties props = new ToolLedgerProperties();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static ToolLedger.Entry entry(String tool, String args, String result, Status status, String approval) {
        return new ToolLedger.Entry(tool, args, result, status, approval);
    }

    private static ToolLedger ledger(ToolLedger.Entry... entries) {
        return new ToolLedger(List.of(entries), 0);
    }

    private static ChatTurn assistantWith(String text, ToolLedger ledger) {
        return new ChatTurn(MessageRole.ASSISTANT, text, T, ledger);
    }

    @Test
    void theBlockHasTheHeaderThenOneLinePerCall() {
        String block =
                ToolLedgerReplay.render(
                        ledger(
                                entry("create_file", "{\"path\":\"a.md\"}", "{\"fileId\":\"f1\"}", Status.OK, null),
                                entry("send_mail", "{\"to\":\"x\"}", "", Status.AWAITING_APPROVAL, "req-9")),
                        props);

        String[] lines = block.split("\n");
        assertEquals(props.getHeader(), lines[0]);
        assertEquals("- create_file({\"path\":\"a.md\"}) -> ok: {\"fileId\":\"f1\"}", lines[1]);
        assertEquals(
                "- send_mail({\"to\":\"x\"}) -> not run yet, awaiting human approval, approval request req-9",
                lines[2]);
        assertEquals(3, lines.length);
    }

    @Test
    void aLedgerWithNothingRendersNothing() {
        assertEquals("", ToolLedgerReplay.render(null, props));
        assertEquals("", ToolLedgerReplay.render(ToolLedger.EMPTY, props));
    }

    @Test
    void callsPastTheBlocksCharacterBudgetAreCountedNotListed() {
        props.setMaxReplayCharsPerTurn(props.getHeader().length() + 120);
        List<ToolLedger.Entry> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(entry("tool_" + i, "{}", "result number " + i, Status.OK, null));
        }

        String block = ToolLedgerReplay.render(new ToolLedger(many, 3), props);

        assertTrue(block.contains("tool_0"), "the first call is always listed");
        assertFalse(block.contains("tool_9"), block);
        assertTrue(block.length() <= props.getMaxReplayCharsPerTurn() + 80, "bounded: " + block.length());
        String last = block.substring(block.lastIndexOf('\n') + 1);
        assertTrue(last.startsWith("- ...and "), last);
        int notListed = Integer.parseInt(last.replaceAll("\\D+", ""));
        assertEquals(10 + 3 - (block.split("\n").length - 2), notListed, "every call is either listed or counted: " + block);
    }

    @Test
    void theRecordIsFoldedAheadOfTheReplyAndStaysOnlyOnTheTurnsItWasFoldedInto() {
        ChatTurn turn = assistantWith("I created the report.", ledger(entry("create_file", "{}", "ok", Status.OK, null)));

        ChatTurn view = ToolLedgerReplay.modelView(turn, props, true).orElseThrow();

        assertTrue(view.content().startsWith(props.getHeader()));
        assertTrue(view.content().endsWith("\n\nI created the report."));
        assertNotNull(view.tools(), "a returned turn with tools() set is one whose record was folded in");
        assertEquals("I created the report.", turn.content(), "the stored turn is untouched");
    }

    @Test
    void onlyTheNewestTurnsThatCarryARecordShowIt() {
        props.setReplayTurns(2);
        List<ChatTurn> stored = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            stored.add(new ChatTurn(MessageRole.USER, "u" + i, T));
            stored.add(assistantWith("a" + i, ledger(entry("tool_" + i, "{}", "r", Status.OK, null))));
        }

        List<ChatTurn> view = ToolLedgerReplay.modelView(stored, props);

        assertEquals(8, view.size());
        assertEquals("a0", view.get(1).content(), "the oldest turns are left to the summary");
        assertNull(view.get(1).tools());
        assertEquals("a1", view.get(3).content());
        assertTrue(view.get(5).content().contains("tool_2"));
        assertTrue(view.get(7).content().contains("tool_3"));
    }

    @Test
    void aFailedReplyWithoutToolsIsStillKeptOutOfWhatTheModelIsShown() {
        List<ChatTurn> stored =
                List.of(new ChatTurn(MessageRole.USER, "hi", T), ChatTurn.failedAssistant("The provider rejected our credentials."));

        assertEquals(1, ToolLedgerReplay.modelView(stored, props).size());
    }

    @Test
    void aFailedReplyThatCalledToolsIsShownAsTheRecordPlusAFailureNoteNeverTheErrorText() {
        ChatTurn failed =
                ChatTurn.failedAssistant("The provider rejected our credentials.")
                        .withTools(ledger(entry("create_file", "{}", "ok", Status.OK, null)));

        List<ChatTurn> view = ToolLedgerReplay.modelView(List.of(new ChatTurn(MessageRole.USER, "hi", T), failed), props);

        assertEquals(2, view.size());
        assertTrue(view.get(1).content().contains("create_file"));
        assertTrue(view.get(1).content().endsWith(props.getFailedReplyNote()));
        assertFalse(view.get(1).content().contains("credentials"));
    }

    @Test
    void withReplayOffTheRecordIsNotShownAndAFailedReplyIsDroppedAsBefore() {
        props.setReplayEnabled(false);
        ChatTurn failed = ChatTurn.failedAssistant("boom").withTools(ledger(entry("t", "{}", "r", Status.OK, null)));
        ChatTurn ok = assistantWith("done", ledger(entry("t", "{}", "r", Status.OK, null)));

        List<ChatTurn> view = ToolLedgerReplay.modelView(List.of(failed, ok), props);

        assertEquals(1, view.size());
        assertEquals("done", view.get(0).content());
        assertNull(view.get(0).tools());
    }

    @Test
    void theLedgerRoundTripsThroughTheStoredJson() throws Exception {
        ChatTurn turn =
                assistantWith(
                        "done",
                        new ToolLedger(
                                List.of(
                                        entry("a", "{\"x\":1}", "r", Status.OK, null),
                                        entry("b", "{}", "", Status.AWAITING_APPROVAL, "req-1")),
                                2));

        ChatTurn back = mapper.readValue(mapper.writeValueAsString(turn), ChatTurn.class);

        assertEquals(turn, back);
        assertEquals(2, back.tools().omitted());
        assertEquals(Status.AWAITING_APPROVAL, back.tools().entries().get(1).status());
    }

    @Test
    void aTurnWithoutToolsWritesNoToolsKeyAndHistoryWrittenBeforeTheLedgerStillReads() throws Exception {
        String written = mapper.writeValueAsString(new ChatTurn(MessageRole.ASSISTANT, "hi", T));
        assertFalse(written.contains("tools"), "stored history of tool-free turns is byte-for-byte what it was: " + written);

        String before = "{\"role\":\"ASSISTANT\",\"content\":\"hi\",\"timestamp\":\"2026-10-01T00:00:00Z\"}";
        ChatTurn read = mapper.readValue(before, ChatTurn.class);
        assertEquals("hi", read.content());
        assertNull(read.tools());
    }

    @Test
    void aLedgerThatRecordsNothingIsNormalisedAwayOnTheTurn() {
        assertNull(assistantWith("hi", ToolLedger.EMPTY).tools());
        assertNull(ChatTurn.assistant("hi", null).tools());
    }

    @Test
    void aRecordOnlyTurnIsAlwaysShownToTheModelAndTakesNoPlaceInTheReplayCount() {
        ChatTurn outcome =
                ChatTurn.toolRecord(
                        ledger(
                                entry(
                                        "web_search",
                                        "{\"q\":\"x\"}",
                                        "[approved action 'web_search' completed: CONF-1]",
                                        Status.OK,
                                        "req-1, decided by alice")));
        assertTrue(ChatTurn.isToolRecordOnly(outcome));
        assertFalse(ChatTurn.isToolRecordOnly(assistantWith("text", outcome.tools())));
        assertFalse(ChatTurn.isToolRecordOnly(ChatTurn.assistant("")));

        // Replay switched off and no slot left: the record is still what the model reads for this turn.
        props.setReplayEnabled(false);
        props.setReplayTurns(0);
        List<ChatTurn> view = ToolLedgerReplay.modelView(List.of(ChatTurn.user("go"), outcome), props);

        assertEquals(2, view.size());
        String text = view.get(1).content();
        assertTrue(text.startsWith(props.getHeader()), text);
        assertTrue(text.contains("- web_search({\"q\":\"x\"}) -> ok: [approved action 'web_search' completed: CONF-1]"), text);
        assertTrue(text.contains("approval request req-1, decided by alice"), text);
        assertNotNull(view.get(1).tools(), "folded in, so the caller adds the trust note");
    }

    @Test
    void aRecordOnlyTurnDoesNotPushAnOrdinaryTurnsRecordOutOfTheReplayWindow() {
        props.setReplayTurns(1);
        ChatTurn worked = assistantWith("Done.", ledger(entry("create_file", "{}", "f1", Status.OK, null)));
        ChatTurn outcome = ChatTurn.toolRecord(ledger(entry("send_mail", "{}", "[sent]", Status.OK, "r")));

        List<ChatTurn> view = ToolLedgerReplay.modelView(List.of(worked, outcome), props);

        assertTrue(view.get(0).content().contains("create_file"), view.get(0).content());
        assertTrue(view.get(1).content().contains("send_mail"), view.get(1).content());
    }
}
