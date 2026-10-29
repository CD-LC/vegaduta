package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.guardrails.ToolStepLimitProperties.Stage;
import org.junit.jupiter.api.Test;

/**
 * {@link ToolStepLimitProperties} ships its defaults from {@code /guardrails/step-limit.properties}
 * (grace 10, supervised divisor 2 / min 3, autonomous multiplier 2) and turns them into the
 * three-stage answer a call past the turn's ceiling gets: WITHIN, EXHAUSTED (answered with the
 * budget-exhausted tool result), then ABORT (the turn is stopped). Setters clamp hostile values
 * instead of storing them, and {@code withNumbers}/{@code withGraceCalls} copy rather than mutate
 * the shared platform object.
 */
class ToolStepLimitPropertiesTest {

    @Test
    void classpathDefaultsAreLoaded() {
        ToolStepLimitProperties props = new ToolStepLimitProperties();
        assertEquals(10, props.getGraceCalls());
        assertEquals(2, props.getSupervisedDivisor());
        assertEquals(3, props.getSupervisedMinSteps());
        assertEquals(2, props.getAutonomousMultiplier());
        assertTrue(props.getExhaustedMessage().contains("{limit}"),
            "the shipped text interpolates the ceiling");
    }

    @Test
    void stageOfWalksWithinThenExhaustedThenAbort() {
        ToolStepLimitProperties props = new ToolStepLimitProperties();
        props.setGraceCalls(2);
        int ceiling = 5;
        assertEquals(Stage.WITHIN, props.stageOf(1, ceiling));
        assertEquals(Stage.WITHIN, props.stageOf(5, ceiling), "the ceiling itself is still within");
        assertEquals(Stage.EXHAUSTED, props.stageOf(6, ceiling));
        assertEquals(Stage.EXHAUSTED, props.stageOf(7, ceiling), "grace calls are exhausted, not aborted");
        assertEquals(Stage.ABORT, props.stageOf(8, ceiling));
    }

    @Test
    void zeroGraceAbortsAtTheFirstCallOverTheCeiling() {
        ToolStepLimitProperties props = new ToolStepLimitProperties();
        props.setGraceCalls(0);
        assertEquals(Stage.WITHIN, props.stageOf(5, 5));
        assertEquals(Stage.ABORT, props.stageOf(6, 5));
    }

    @Test
    void supervisedCeilingIsTheDividedBaseButNeverBelowTheFloor() {
        ToolStepLimitProperties props = new ToolStepLimitProperties();
        props.setSupervisedDivisor(2);
        props.setSupervisedMinSteps(3);
        assertEquals(10, props.supervisedCeiling(20));
        assertEquals(3, props.supervisedCeiling(4), "a small base still gets the minimum");
        assertEquals(3, props.supervisedCeiling(0));
    }

    @Test
    void autonomousCeilingScalesTheBase() {
        ToolStepLimitProperties props = new ToolStepLimitProperties();
        props.setAutonomousMultiplier(3);
        assertEquals(30, props.autonomousCeiling(10));
    }

    @Test
    void settersClampOutOfRangeValues() {
        ToolStepLimitProperties props = new ToolStepLimitProperties();
        props.setGraceCalls(-7);
        props.setSupervisedDivisor(0);
        props.setSupervisedMinSteps(-2);
        props.setAutonomousMultiplier(0);
        assertEquals(0, props.getGraceCalls());
        assertEquals(1, props.getSupervisedDivisor());
        assertEquals(1, props.getSupervisedMinSteps());
        assertEquals(1, props.getAutonomousMultiplier());
    }

    @Test
    void withNumbersCopiesInsteadOfMutating() {
        ToolStepLimitProperties props = new ToolStepLimitProperties();
        props.setExhaustedMessage("stop at {limit}");
        ToolStepLimitProperties copy = props.withNumbers(1, 4, 2, 5);
        assertNotSame(props, copy);
        assertEquals(1, copy.getGraceCalls());
        assertEquals(4, copy.getSupervisedDivisor());
        assertEquals(2, copy.getSupervisedMinSteps());
        assertEquals(5, copy.getAutonomousMultiplier());
        assertEquals("stop at {limit}", copy.getExhaustedMessage(), "the message is kept");
        assertEquals(10, props.getGraceCalls(), "the original is untouched");
    }

    @Test
    void withGraceCallsChangesOnlyTheGrace() {
        ToolStepLimitProperties props = new ToolStepLimitProperties();
        props.setGraceCalls(0);
        assertEquals(4, props.withGraceCalls(4).getGraceCalls());
        assertEquals(0, props.getGraceCalls());
    }

    @Test
    void exhaustedResultIsTheSharedBudgetExhaustedShapeWithTheLimitFilled() {
        ToolStepLimitProperties props = new ToolStepLimitProperties();
        props.setExhaustedMessage("Budget used: {limit} calls");
        String json = props.exhaustedResult(12);
        assertTrue(ToolBudgetExhausted.isBudgetExhausted(json), json);
        assertTrue(json.contains("Budget used: 12 calls"), json);
        assertFalse(json.contains("{limit}"), "the placeholder is always substituted");
    }
}
