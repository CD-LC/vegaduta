package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RunModeTest {

    @Test
    void onlyDraftAndTestHonourPins() {
        assertFalse(RunMode.PRODUCTION.honoursPins());
        assertTrue(RunMode.DRAFT.honoursPins());
        assertTrue(RunMode.TEST.honoursPins());
    }

    @Test
    void aRequestMayAskForProductionOrDraftAndNothingElse() {
        assertEquals(RunMode.PRODUCTION, RunMode.fromRequest(null));
        assertEquals(RunMode.PRODUCTION, RunMode.fromRequest(""));
        assertEquals(RunMode.PRODUCTION, RunMode.fromRequest(" production "));
        assertEquals(RunMode.DRAFT, RunMode.fromRequest("draft"));
        assertEquals(RunMode.DRAFT, RunMode.fromRequest("Draft"));
        assertThrows(IllegalArgumentException.class, () -> RunMode.fromRequest("test"));
        assertThrows(IllegalArgumentException.class, () -> RunMode.fromRequest("prod"));
    }

    @Test
    void anythingUnreadableStaysProductionSoItNeverHonoursPins() {
        assertEquals(RunMode.PRODUCTION, RunMode.fromWire(null));
        assertEquals(RunMode.PRODUCTION, RunMode.fromWire("garbage"));
        assertEquals(RunMode.DRAFT, RunMode.fromWire("draft"));
        assertEquals(RunMode.TEST, RunMode.fromWire("TEST"));
        assertEquals("draft", RunMode.DRAFT.wire());
    }

    @Test
    void theScopeForAModeNeverExistsInProduction() {
        assertNull(ExpressionScope.forMode(null, RunMode.PRODUCTION));
        assertNull(ExpressionScope.forMode(null, null));
        ExpressionScope iteration = ExpressionScope.iteration("a", 1, List.of("a", "b"));
        assertSame(iteration, ExpressionScope.forMode(iteration, RunMode.PRODUCTION));
        assertEquals("production", iteration.modeOrDefault());
    }

    @Test
    void aDraftScopeKeepsTheIterationFactsAndAStepTestNeverHonoursPins() {
        ExpressionScope s = ExpressionScope.forMode(ExpressionScope.iteration("a", 1, List.of("a", "b")), RunMode.DRAFT);
        assertEquals("a", s.item());
        assertEquals(1, s.index());
        assertEquals(List.of("a", "b"), s.items());
        assertEquals("draft", s.mode());
        assertTrue(s.honoursPins());
        assertFalse(ExpressionScope.test().honoursPins(), "the step test runs the node under test for real");
    }
}
