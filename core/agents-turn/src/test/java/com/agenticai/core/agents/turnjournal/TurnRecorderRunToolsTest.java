package com.agenticai.core.agents.turnjournal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The INTAKE event shows the tool set a run was narrowed to, and only when it was. */
class TurnRecorderRunToolsTest {

    private final UUID agent = UUID.randomUUID();

    private String intakeSummary(List<String> runTools) {
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        TurnRecorder rec = TurnRecorder.start(journal, agent, UUID.randomUUID(), "s");
        rec.intake("hello", 0, runTools == null ? 5 : runTools.size(), null, runTools);
        return journal.read(agent, rec.turnId()).get(0).summary();
    }

    @Test
    void anUnnarrowedTurnWritesNoRunToolsField() {
        assertFalse(intakeSummary(null).contains("runTools"));
    }

    @Test
    void aNarrowedTurnListsItsTools() {
        String summary = intakeSummary(List.of("web_search", "fetch_web_page"));

        assertTrue(summary.contains("tools=2"), summary);
        assertTrue(summary.contains("runTools=web_search,fetch_web_page"), summary);
    }

    @Test
    void aTurnNarrowedToNoToolsSaysSo() {
        String summary = intakeSummary(List.of());

        assertTrue(summary.contains("tools=0"), summary);
        assertTrue(summary.contains("runTools=none"), summary);
    }

    @Test
    void aLongListIsSummarisedByCountNotCutMidName() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add("some_long_tool_name_" + i);
        }

        String summary = intakeSummary(many);

        assertTrue(summary.length() <= 240, "the summary stays within the journal's cap: " + summary.length());
        String field = summary.substring(summary.indexOf("runTools="));
        assertTrue(field.matches("runTools=(some_long_tool_name_\\d+,)+\\+\\d+more"), field);
        int shown = field.split(",").length - 1;
        assertEquals(30 - shown, Integer.parseInt(field.replaceAll(".*\\+(\\d+)more.*", "$1")));
    }
}
