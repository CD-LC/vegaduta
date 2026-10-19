package com.agenticai.core.agents.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RunBudgetCapsTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static void assertNum(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void noAgentValueMeansThePlatformCap() {
        assertNum("2", RunBudgetCaps.effectiveCap(bd("2.0"), null, true, bd("10")));
        assertNum("2", RunBudgetCaps.effectiveCap(bd("2.0"), BigDecimal.ZERO, true, bd("10")));
        assertNum("2", RunBudgetCaps.effectiveCap(bd("2.0"), bd("-1"), true, bd("10")));
    }

    @Test
    void agentCanLowerThePlatformCapButNeverRaiseIt() {
        assertNum("0.5", RunBudgetCaps.effectiveCap(bd("2.0"), bd("0.5"), true, bd("10")));
        assertNum("2", RunBudgetCaps.effectiveCap(bd("2.0"), bd("5"), true, bd("10")));
    }

    @Test
    void overrideSwitchedOffIgnoresTheAgentValue() {
        assertNum("2", RunBudgetCaps.effectiveCap(bd("2.0"), bd("0.5"), false, bd("10")));
    }

    @Test
    void withNoPlatformCapTheAgentValueIsUsedUpToThePerAgentMaximum() {
        assertNum("3", RunBudgetCaps.effectiveCap(BigDecimal.ZERO, bd("3"), true, bd("10")));
        assertNum("10", RunBudgetCaps.effectiveCap(BigDecimal.ZERO, bd("50"), true, bd("10")));
        assertNum("50", RunBudgetCaps.effectiveCap(null, bd("50"), true, BigDecimal.ZERO));
        assertNum("0", RunBudgetCaps.effectiveCap(null, null, true, bd("10")));
    }

    @Test
    void ceilingIsThePlatformCapElseThePerAgentMaximumElseNone() {
        assertNum("2", RunBudgetCaps.ceiling(bd("2"), bd("10")));
        assertNum("10", RunBudgetCaps.ceiling(BigDecimal.ZERO, bd("10")));
        assertNull(RunBudgetCaps.ceiling(BigDecimal.ZERO, BigDecimal.ZERO));
        assertNull(RunBudgetCaps.ceiling(null, null));
    }

    @Test
    void validateAcceptsNullAndValuesUpToTheCeiling() {
        assertNull(RunBudgetCaps.validateForStorage(null, bd("2"), bd("10")));
        assertNum("2", RunBudgetCaps.validateForStorage(bd("2"), bd("2"), bd("10")));
        assertNum("0.25", RunBudgetCaps.validateForStorage(bd("0.2500000"), bd("2"), bd("10")));
        assertEquals(4, RunBudgetCaps.validateForStorage(bd("1"), bd("2"), bd("10")).scale());
        assertNum("7", RunBudgetCaps.validateForStorage(bd("7"), BigDecimal.ZERO, bd("10")));
    }

    @Test
    void validateRejectsNonPositiveTooPreciseAndAboveCeiling() {
        assertThrows(IllegalArgumentException.class,
            () -> RunBudgetCaps.validateForStorage(BigDecimal.ZERO, bd("2"), bd("10")));
        assertThrows(IllegalArgumentException.class,
            () -> RunBudgetCaps.validateForStorage(bd("-0.5"), bd("2"), bd("10")));
        assertThrows(IllegalArgumentException.class,
            () -> RunBudgetCaps.validateForStorage(bd("0.00001"), bd("2"), bd("10")));
        assertThrows(IllegalArgumentException.class,
            () -> RunBudgetCaps.validateForStorage(bd("2.01"), bd("2"), bd("10")));
        assertThrows(IllegalArgumentException.class,
            () -> RunBudgetCaps.validateForStorage(bd("11"), BigDecimal.ZERO, bd("10")));
        assertThrows(IllegalArgumentException.class,
            () -> RunBudgetCaps.validateForStorage(bd("1000000"), BigDecimal.ZERO, BigDecimal.ZERO));
    }
}
