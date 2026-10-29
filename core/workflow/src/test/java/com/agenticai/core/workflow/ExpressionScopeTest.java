package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link ExpressionScope}: iteration/test factories, pin honouring and the mode stamping. */
class ExpressionScopeTest {

    @Test
    void iterationCarriesTheItemsWhenTheyFit() {
        ExpressionScope s =
                ExpressionScope.iteration("{\"a\":1}", 1, List.of("{\"a\":1}", "{\"b\":2}"));
        assertEquals("{\"a\":1}", s.item());
        assertEquals(1, s.index());
        assertEquals(List.of("{\"a\":1}", "{\"b\":2}"), s.items());
        assertNull(s.mode());
        assertNull(s.supplied());
    }

    @Test
    void iterationDropsAnOversizedItemSet() {
        String big = "x".repeat(ExpressionScope.MAX_ITEMS_CHARS);
        ExpressionScope s = ExpressionScope.iteration(big, 0, List.of(big, big));
        assertNull(s.items(), "$items above the char budget is not carried");
        ExpressionScope nullItems = ExpressionScope.iteration("i", 0, null);
        assertNull(nullItems.items());
    }

    @Test
    void testScopeSkipsRecordingAndHonoursNoPinsOfItsOwn() {
        ExpressionScope t = ExpressionScope.test();
        assertEquals("test", t.mode());
        assertTrue(t.recordingSkipped());
        assertNotNull(t.supplied());
        assertFalse(t.honoursPins(), "a step test runs the node under test for real");
        assertFalse(t.simulatesSends());
        assertEquals(
                Map.of(),
                ExpressionScope.test(null).supplied(),
                "null supplied normalises to empty");
    }

    @Test
    void honoursPinsOnlyWhenNothingWasSupplied() {
        ExpressionScope draft = new ExpressionScope(null, null, null, "draft", null, null);
        assertTrue(draft.honoursPins());
        ExpressionScope prod = new ExpressionScope(null, null, null, "production", null, null);
        assertFalse(prod.honoursPins());
        ExpressionScope nullMode = new ExpressionScope(null, null, null, null, null, null);
        assertFalse(nullMode.honoursPins(), "an absent mode reads as production");
        ExpressionScope weird = new ExpressionScope(null, null, null, "bogus", null, null);
        assertFalse(weird.honoursPins());
    }

    @Test
    void simulatesSendsOnlyForTheDryRunMode() {
        ExpressionScope dry = new ExpressionScope(null, null, null, "draft_dry_run", null, null);
        assertTrue(dry.simulatesSends());
        ExpressionScope draft = new ExpressionScope(null, null, null, "draft", null, null);
        assertFalse(draft.simulatesSends());
        ExpressionScope supplied =
                new ExpressionScope(null, null, null, "draft_dry_run", null, Map.of());
        assertFalse(supplied.simulatesSends(), "a step test never simulates sends");
    }

    @Test
    void forModeStampsTheWireMode() {
        ExpressionScope it = ExpressionScope.iteration("i", 2, List.of("i"));
        ExpressionScope stamped = ExpressionScope.forMode(it, RunMode.DRAFT);
        assertEquals("draft", stamped.mode());
        assertEquals("i", stamped.item());
        assertEquals(2, stamped.index());

        assertSame(it, ExpressionScope.forMode(it, RunMode.PRODUCTION));
        assertSame(it, ExpressionScope.forMode(it, null));
        ExpressionScope fresh = ExpressionScope.forMode(null, RunMode.TEST);
        assertEquals("test", fresh.mode());
        assertSame(null, ExpressionScope.forMode(null, RunMode.PRODUCTION));
    }

    @Test
    void seqModeAndRecordingAccessors() {
        assertEquals(0, new ExpressionScope(null, null, null, null, null).seq());
        assertEquals(0, new ExpressionScope(null, -5, null, null, null).seq());
        assertEquals(3, new ExpressionScope(null, 3, null, null, null).seq());

        assertEquals(
                "production", new ExpressionScope(null, null, null, null, null).modeOrDefault());
        assertEquals(
                "production", new ExpressionScope(null, null, null, "  ", null).modeOrDefault());
        assertEquals("draft", new ExpressionScope(null, null, null, "draft", null).modeOrDefault());

        assertFalse(new ExpressionScope(null, null, null, null, null).recordingSkipped());
        assertFalse(new ExpressionScope(null, null, null, null, Boolean.FALSE).recordingSkipped());
        assertTrue(new ExpressionScope(null, null, null, null, Boolean.TRUE).recordingSkipped());
    }
}
