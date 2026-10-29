package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link CachingPolicy} is an agent's two response-cache opt-ins, exact-match and the separate,
 * stronger semantic one. These pin the all-off starting state, that the {@code with*} variants copy
 * rather than mutate, and that turning one on never flips the other - semantic caching is its own
 * opt-in, never implied by the exact-match one.
 */
class CachingPolicyTest {

    @Test
    void offStartsWithBothCachesDisabled() {
        CachingPolicy p = CachingPolicy.off();

        assertFalse(p.isResponseCachingEnabled());
        assertFalse(p.isSemanticResponseCachingEnabled());
    }

    @Test
    void withResponseCachingLeavesSemanticAloneAndDoesNotMutate() {
        CachingPolicy p = CachingPolicy.off().withResponseCaching(true);

        assertTrue(p.isResponseCachingEnabled());
        assertFalse(p.isSemanticResponseCachingEnabled());
    }

    @Test
    void withSemanticCachingPreservesTheExactMatchFlag() {
        CachingPolicy base = CachingPolicy.off().withResponseCaching(true);
        CachingPolicy semantic = base.withSemanticResponseCaching(true);

        assertTrue(semantic.isResponseCachingEnabled());
        assertTrue(semantic.isSemanticResponseCachingEnabled());
        assertNotSame(base, semantic);
        assertFalse(
                base.isSemanticResponseCachingEnabled(),
                "the original is untouched - an immutable copy, not a mutation");
    }

    @Test
    void equalityIsOnBothFlags() {
        CachingPolicy both =
                CachingPolicy.off().withResponseCaching(true).withSemanticResponseCaching(true);

        assertEquals(
                both,
                CachingPolicy.off().withResponseCaching(true).withSemanticResponseCaching(true));
        assertEquals(both.hashCode(), both.hashCode());
        assertNotEquals(both, CachingPolicy.off().withResponseCaching(true));
        assertNotEquals(both, CachingPolicy.off());
        assertNotEquals(both, null);
        assertNotEquals(both, "a string");
    }
}
