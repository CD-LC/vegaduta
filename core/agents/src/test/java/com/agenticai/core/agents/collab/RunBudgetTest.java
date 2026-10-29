package com.agenticai.core.agents.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * {@link RunBudget} is the per-turn USD cap shared by every delegate tool of one turn. These pin
 * the estimate (branches x average tokens at the configured blended rates), the
 * never-less-than-one-branch floor on {@code recordDelegate}, and {@code affordableBranches}'s
 * "as many as fit, at least 0" contract - the guard is a runaway stop, not billing.
 */
class RunBudgetTest {

    /** $1.00/M in, $5.00/M out, 1000-token average branch -> 0.0018 USD per estimated branch. */
    private static RunBudget budget(String cap) {
        return new RunBudget(new BigDecimal(cap), new BigDecimal("1"), new BigDecimal("5"), 1000);
    }

    @Test
    void aZeroCapDisablesTheGuardEntirely() {
        RunBudget b = budget("0");

        assertFalse(b.isEnabled());
        b.record("x".repeat(10_000), "y".repeat(10_000));
        assertFalse(b.isExceeded(), "disabled means never exceeded");
        assertEquals(7, b.affordableBranches(7), "disabled means everything is affordable");
    }

    @Test
    void theBranchEstimateUsesTheConfiguredRatesAndMix() {
        RunBudget b = budget("1.00");

        // 1000-token branch = 800 in @ $1/M + 200 out @ $5/M = 0.0008 + 0.001 = 0.0018.
        assertEquals(0, b.estimateUsd(1).compareTo(new BigDecimal("0.0018")));
        assertEquals(0, b.estimateUsd(10).compareTo(new BigDecimal("0.018")));
        assertEquals(0, b.estimateUsd(0).compareTo(BigDecimal.ZERO));
        assertEquals(0, b.estimateUsd(-3).compareTo(BigDecimal.ZERO), "negative branches cost nothing");
    }

    @Test
    void recordEstimatesTokensFromTextLengths() {
        RunBudget b = budget("1.00");

        b.record("x".repeat(400), "y".repeat(400));
        // 100 tokens in @ $1/M + 100 out @ $5/M = 0.0001 + 0.0005 = 0.0006.
        assertEquals(0, b.spentUsd().compareTo(new BigDecimal("0.0006")));

        // Null text is zero tokens, not an error.
        b.record(null, null);
        assertEquals(0, b.spentUsd().compareTo(new BigDecimal("0.0006")));
    }

    @Test
    void recordDelegateFloorsAtOneBranchEstimate() {
        RunBudget b = budget("1.00");

        // A trivially short exchange would price at a fraction of a branch, but a delegate ran a
        // whole agent loop - so the floor applies.
        b.recordDelegate("a", "b");
        assertEquals(0, b.spentUsd().compareTo(new BigDecimal("0.0018")));

        // A genuinely large text prices above the floor on its own.
        b.recordDelegate("x".repeat(40_000), "y".repeat(40_000));
        // 10000 in @ $1/M + 10000 out @ $5/M = 0.01 + 0.05 = 0.06.
        assertEquals(0, b.spentUsd().compareTo(new BigDecimal("0.0618")));
    }

    @Test
    void exceedingNeedsBothEnabledAndSpentAtCap() {
        RunBudget b = budget("0.005");

        assertFalse(b.isExceeded());
        b.record("x".repeat(4_000), "y".repeat(4_000));
        // 1000 in + 1000 out = 0.001 + 0.005 = 0.006 >= 0.005.
        assertTrue(b.isExceeded());
    }

    @Test
    void affordableBranchesFitsWhatRemains() {
        RunBudget b = budget("0.0054");

        // 3 branches fit exactly (3 x 0.0018 = 0.0054).
        assertEquals(3, b.affordableBranches(10));
        assertEquals(2, b.affordableBranches(2), "never more than requested");

        b.recordDelegate("a", "b");
        // 0.0054 - 0.0018 = 0.0036 left = 2 more branches.
        assertEquals(2, b.affordableBranches(10));

        b.recordDelegate("a", "b");
        b.recordDelegate("a", "b");
        assertEquals(0, b.affordableBranches(10), "nothing left fits - never negative");
    }

    @Test
    void stopMessageNamesSpendAndCap() {
        RunBudget b = budget("0.01");
        b.recordDelegate("a", "b");

        String message = b.stopMessage();

        assertTrue(message.contains("$0.00"), message);
        assertTrue(message.contains("$0.01"), message);
        assertTrue(message.contains("cost cap") || message.contains("per-run cap"), message);
        assertEquals("run_budget_exceeded", RunBudget.STOP_REASON);
    }

    @Test
    void nullConstructorArgumentsCollapseToZero() {
        RunBudget b = new RunBudget(null, null, null, 0);

        assertFalse(b.isEnabled());
        assertEquals(0, b.capUsd().compareTo(BigDecimal.ZERO));
        // avgBranchTokens floors at 1, so the estimate is computable rather than a divide-by-zero.
        b.record("a", "b");
        assertEquals(0, b.spentUsd().compareTo(BigDecimal.ZERO));
    }
}
