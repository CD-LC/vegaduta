package com.agenticai.core.agents.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link AgentGenerationProperties} reads the platform's generation-length bounds from {@code
 * /agents/generation.properties}. These pin the shipped values - the ceiling every create and
 * update validates {@code maxTokens} against - and the floor the setters enforce (1 is the least
 * sane bound; 0 would make every agent reply empty).
 */
class AgentGenerationPropertiesTest {

    @Test
    void defaultsComeFromTheClasspathResource() {
        AgentGenerationProperties p = new AgentGenerationProperties();

        assertEquals(8192, p.getMaxTokensCeiling());
        assertTrue(p.getInferredMaxTokensFloor() >= 1);
        assertTrue(
                p.getInferredMaxTokensFloor() <= p.getMaxTokensCeiling(),
                "the Simple-mode floor must be reachable under the ceiling");
    }

    @Test
    void settersClampAtOne() {
        AgentGenerationProperties p = new AgentGenerationProperties();

        p.setMaxTokensCeiling(0);
        p.setInferredMaxTokensFloor(-10);

        assertEquals(1, p.getMaxTokensCeiling());
        assertEquals(1, p.getInferredMaxTokensFloor());

        p.setMaxTokensCeiling(16384);
        p.setInferredMaxTokensFloor(256);
        assertEquals(16384, p.getMaxTokensCeiling());
        assertEquals(256, p.getInferredMaxTokensFloor());
    }
}
