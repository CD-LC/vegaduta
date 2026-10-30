package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The per-agent Anthropic extended-thinking effort vocabulary (low | medium | high). Anything else
 * - including null, blank and mixed-case noise - normalizes to null so a bad value can never reach
 * the provider factory.
 */
class ThinkingEffortTest {

    @Test
    void theThreeAllowedLevelsNormalizeToThemselves() {
        assertEquals("low", ThinkingEffort.normalize("low"));
        assertEquals("medium", ThinkingEffort.normalize("medium"));
        assertEquals("high", ThinkingEffort.normalize("high"));
    }

    @Test
    void casingAndSurroundingWhitespaceAreIgnored() {
        assertEquals("high", ThinkingEffort.normalize("  HIGH  "));
        assertEquals("medium", ThinkingEffort.normalize("Medium"));
    }

    @Test
    void nullBlankAndUnknownValuesNormalizeToNull() {
        assertNull(ThinkingEffort.normalize(null));
        assertNull(ThinkingEffort.normalize(""));
        assertNull(ThinkingEffort.normalize("   "));
        assertNull(ThinkingEffort.normalize("ultra"));
        assertNull(ThinkingEffort.normalize("lowest"));
    }

    @Test
    void theProviderOptionsKeyIsTheOneAnthropicThinkingReads() {
        assertEquals("reasoningEffort", ThinkingEffort.OPTION_KEY);
    }
}
