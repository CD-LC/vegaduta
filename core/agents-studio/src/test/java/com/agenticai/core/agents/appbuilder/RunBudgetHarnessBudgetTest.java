package com.agenticai.core.agents.appbuilder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.collab.RunBudget;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RunBudgetHarnessBudgetTest {

    @Test
    void disabledRunBudgetNeverExhausts() {
        RunBudgetHarnessBudget b = new RunBudgetHarnessBudget(
            new RunBudget(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, 1000));
        b.record("x".repeat(100_000), "y".repeat(100_000));
        assertFalse(b.exhausted());
    }

    @Test
    void spendingPastTheCapExhausts() {
        RunBudgetHarnessBudget b = new RunBudgetHarnessBudget(
            new RunBudget(new BigDecimal("0.01"), new BigDecimal("10"), new BigDecimal("10"), 1000));
        assertFalse(b.exhausted());
        b.record("x".repeat(40_000), "y".repeat(40_000));
        assertTrue(b.exhausted());
        assertTrue(b.stopMessage().contains("Run budget reached"));
    }

    @Test
    void nullBudgetIsInert() {
        RunBudgetHarnessBudget b = new RunBudgetHarnessBudget(null);
        b.record("a", "b");
        assertFalse(b.exhausted());
    }
}
