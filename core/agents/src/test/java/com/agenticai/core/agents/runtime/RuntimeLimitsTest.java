package com.agenticai.core.agents.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link RuntimeLimits} is the one immutable value object used for the agent's overrides, the
 * tenant's defaults and the API body of both. These pin the null-means-not-set contract, the
 * {@link RuntimeSetting}-keyed {@code get}/{@code with} round trip, and {@code capToolSteps}.
 */
class RuntimeLimitsTest {

    @Test
    void noneIsEmptyAndReadsNullForEverySetting() {
        RuntimeLimits limits = RuntimeLimits.none();

        assertTrue(limits.isEmpty());
        for (RuntimeSetting setting : RuntimeSetting.values()) {
            assertNull(limits.get(setting), setting.name());
        }
        assertNull(limits.getMaxToolSteps());
        assertNull(limits.getTurnTimeoutSeconds());
        assertNull(limits.getToolTimeoutSeconds());
        assertNull(limits.getToolOutputMaxChars());
        assertNull(limits.getRetrievalTopK());
        assertNull(limits.getRetrievalMinScore());
        assertNull(limits.getContinuationPasses());
    }

    @Test
    void theCanonicalConstructorSetsEachField() {
        RuntimeLimits limits = new RuntimeLimits(5, 60, 10, 4000, 8, 0.5, 2);

        assertEquals(5, limits.getMaxToolSteps());
        assertEquals(60, limits.getTurnTimeoutSeconds());
        assertEquals(10, limits.getToolTimeoutSeconds());
        assertEquals(4000, limits.getToolOutputMaxChars());
        assertEquals(8, limits.getRetrievalTopK());
        assertEquals(0.5, limits.getRetrievalMinScore());
        assertEquals(2, limits.getContinuationPasses());
        assertFalse(limits.isEmpty());
    }

    @Test
    void withSetsReadsAndClearsOneSettingAtATime() {
        RuntimeLimits limits = RuntimeLimits.none().with(RuntimeSetting.MAX_TOOL_STEPS, 7);

        assertEquals(7, limits.get(RuntimeSetting.MAX_TOOL_STEPS));
        assertNull(limits.get(RuntimeSetting.TURN_TIMEOUT_SECONDS));
        // Clearing the only set field returns to the empty state.
        assertTrue(limits.with(RuntimeSetting.MAX_TOOL_STEPS, null).isEmpty());
    }

    @Test
    void withCoversEverySettingAndPreservesTheRest() {
        RuntimeLimits limits = RuntimeLimits.none()
            .with(RuntimeSetting.TURN_TIMEOUT_SECONDS, 30)
            .with(RuntimeSetting.TOOL_TIMEOUT_SECONDS, 5)
            .with(RuntimeSetting.TOOL_OUTPUT_MAX_CHARS, 2000)
            .with(RuntimeSetting.RETRIEVAL_TOP_K, 4)
            .with(RuntimeSetting.RETRIEVAL_MIN_SCORE, 0.75)
            .with(RuntimeSetting.CONTINUATION_PASSES, 3);

        assertEquals(30, limits.getTurnTimeoutSeconds());
        assertEquals(5, limits.getToolTimeoutSeconds());
        assertEquals(2000, limits.getToolOutputMaxChars());
        assertEquals(4, limits.getRetrievalTopK());
        assertEquals(0.75, limits.getRetrievalMinScore());
        assertEquals(3, limits.getContinuationPasses());
    }

    @Test
    void capToolStepsOverridesOnlyTheStepCeiling() {
        RuntimeLimits cap = RuntimeLimits.capToolSteps(3);

        assertEquals(3, cap.getMaxToolSteps());
        assertNull(cap.getTurnTimeoutSeconds());
    }

    @Test
    void equalityHashCodeAndToString() {
        RuntimeLimits a = new RuntimeLimits(1, 2, 3, 4, 5, 0.5, 6);
        RuntimeLimits b = new RuntimeLimits(1, 2, 3, 4, 5, 0.5, 6);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, RuntimeLimits.none());
        assertNotEquals(a, a.with(RuntimeSetting.RETRIEVAL_TOP_K, 9));
        assertTrue(a.toString().contains("maxToolSteps=1"));
        assertTrue(a.toString().contains("continuationPasses=6"));
        // none() is the shared empty instance.
        assertSame(RuntimeLimits.none(), RuntimeLimits.none());
    }
}
