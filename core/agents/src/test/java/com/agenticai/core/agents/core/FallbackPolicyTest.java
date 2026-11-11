package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.providers.ProviderName;
import org.junit.jupiter.api.Test;

/**
 * {@link FallbackPolicy} is the embedded value object behind {@link Agent}'s fallback
 * provider/model pair and its observability counters. These pin the "null provider clears the
 * model" rule and that the counters are carried across a {@code withFallback} rather than reset.
 */
class FallbackPolicyTest {

    @Test
    void noneHasNoFallbackAndZeroCounters() {
        FallbackPolicy p = FallbackPolicy.none();

        assertNull(p.getProviderName());
        assertNull(p.getModel());
        assertEquals(0, p.getTriggeredCount());
        assertEquals(0, p.getFailedCount());
        assertNull(p.getLastFallbackAt());
    }

    @Test
    void withFallbackSetsProviderAndModel() {
        FallbackPolicy p = FallbackPolicy.none().withFallback(ProviderName.OPENAI, "gpt-4o-mini");

        assertEquals(ProviderName.OPENAI, p.getProviderName());
        assertEquals("gpt-4o-mini", p.getModel());
    }

    @Test
    void aNullProviderClearsTheModelToo() {
        FallbackPolicy p =
                FallbackPolicy.none()
                        .withFallback(ProviderName.OPENAI, "gpt-4o-mini")
                        .withFallback(null, "ignored");

        assertNull(p.getProviderName());
        assertNull(p.getModel());
    }

    @Test
    void equalityCoversEveryField() {
        FallbackPolicy a = FallbackPolicy.none().withFallback(ProviderName.GEMINI, "gemini-flash");
        FallbackPolicy b = FallbackPolicy.none().withFallback(ProviderName.GEMINI, "gemini-flash");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, FallbackPolicy.none());
        assertNotEquals(a, b.withFallback(ProviderName.GEMINI, "other-model"));
        assertNotEquals(a, b.withFallback(ProviderName.OPENAI, "gemini-flash"));
        assertTrue(a.equals(a));
        assertNotEquals(a, null);
    }
}
