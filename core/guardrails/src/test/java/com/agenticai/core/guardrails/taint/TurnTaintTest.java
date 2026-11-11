package com.agenticai.core.guardrails.taint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link TurnTaint} is the per-turn record of untrusted content reaching the model's context: the
 * first {@code mark} is the signal worth acting on, the remembered source labels are bounded so a
 * hostile loop can't grow it forever, and {@code firstReport} dedupes observe-mode WARN/audit
 * output per key. There is deliberately no way to untaint a turn.
 */
class TurnTaintTest {

    @Test
    void aFreshTurnIsCleanUntilSomethingMarksIt() {
        TurnTaint taint = new TurnTaint();
        assertFalse(taint.isTainted());
        assertTrue(taint.sources().isEmpty());

        assertTrue(taint.mark("fetch_web_page"), "the first mark reports the taint");
        assertTrue(taint.isTainted());
        assertEquals(List.of("fetch_web_page"), taint.sources());
    }

    @Test
    void onlyTheFirstMarkReportsTheTurnTurnedTainted() {
        TurnTaint taint = new TurnTaint();
        assertTrue(taint.mark("a"));
        assertFalse(taint.mark("b"), "later marks just add sources");
        assertFalse(taint.mark("a"), "re-marking the same source also reports false");
        assertEquals(List.of("a", "b"), taint.sources());
    }

    @Test
    void blankAndNullSourcesAreRecordedAsUnknown() {
        TurnTaint taint = new TurnTaint();
        taint.mark(null);
        taint.mark("   ");
        assertTrue(taint.isTainted());
        assertEquals(List.of("unknown"), taint.sources(), "null and blank collapse to one label");
    }

    @Test
    void rememberedSourcesAreBoundedButTheTurnStaysTainted() {
        TurnTaint taint = new TurnTaint();
        for (int i = 0; i < TurnTaint.MAX_SOURCES + 5; i++) {
            taint.mark("source-" + i);
        }
        assertTrue(taint.isTainted());
        assertEquals(TurnTaint.MAX_SOURCES, taint.sources().size());
        assertEquals("source-0", taint.sources().get(0));
    }

    @Test
    void sourcesReturnsAnIndependentSnapshot() {
        TurnTaint taint = new TurnTaint();
        taint.mark("a");
        List<String> snapshot = taint.sources();
        taint.mark("b");
        assertEquals(List.of("a"), snapshot, "a snapshot is not affected by later marks");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("c"));
    }

    @Test
    void firstReportIsTrueOncePerKey() {
        TurnTaint taint = new TurnTaint();
        assertTrue(taint.firstReport("send_email"));
        assertFalse(taint.firstReport("send_email"));
        assertTrue(taint.firstReport("other_tool"));
    }

    @Test
    void firstReportTreatsNullAsItsOwnKey() {
        TurnTaint taint = new TurnTaint();
        assertTrue(taint.firstReport(null));
        assertFalse(taint.firstReport(null), "the null key is reported once, as 'unknown'");
    }

    @Test
    void reportsAreBoundedSoAFloodOfDistinctKeysStopsReporting() {
        TurnTaint taint = new TurnTaint();
        for (int i = 0; i < TurnTaint.MAX_REPORTED; i++) {
            assertTrue(taint.firstReport("key-" + i), "key-" + i);
        }
        assertFalse(taint.firstReport("one-more"), "past the bound nothing new is reported");
        assertFalse(taint.firstReport("key-0"), "a seen key still reports false");
    }
}
