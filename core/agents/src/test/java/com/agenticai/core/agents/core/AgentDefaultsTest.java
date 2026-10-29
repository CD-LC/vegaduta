package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentDefaults} carries the {@code app.agents.*} values a new agent is born with. The only
 * logic is the comma-separated guardrail id parsing - these pin trimming, emptiness dropping, and
 * the immutable set it produces.
 */
class AgentDefaultsTest {

    @Test
    void valuesAreCarriedVerbatim() {
        AgentDefaults defaults = new AgentDefaults("claude-haiku-4-5", 4096, 0.7, 1.0, "jailbreak");

        assertEquals("claude-haiku-4-5", defaults.model());
        assertEquals(4096, defaults.maxTokens());
        assertEquals(0.7, defaults.temperature());
        assertEquals(1.0, defaults.topP());
        assertEquals(Set.of("jailbreak"), defaults.guardrailIds());
    }

    @Test
    void guardrailIdsParseAsACommaSeparatedSet() {
        AgentDefaults defaults =
            new AgentDefaults("m", 1, 0, 0, " jailbreak , profanity,,pii , ");

        assertEquals(Set.of("jailbreak", "profanity", "pii"), defaults.guardrailIds());
        assertThrows(UnsupportedOperationException.class, () -> defaults.guardrailIds().add("x"));
    }

    @Test
    void anEmptyGuardrailsPropertyRestoresBornUnguarded() {
        assertTrue(new AgentDefaults("m", 1, 0, 0, "").guardrailIds().isEmpty());
        assertTrue(new AgentDefaults("m", 1, 0, 0, " , , ").guardrailIds().isEmpty());
    }
}
