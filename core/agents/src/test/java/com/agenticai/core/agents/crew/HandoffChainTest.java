package com.agenticai.core.agents.crew;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link HandoffChain}'s two guarantees in isolation (no Spring context, no DB) - the depth bound
 * and cycle detection that together are the entire loop/recursion guard for "Crew"-style
 * multi-agent handoff. Mirrors {@code AgentExperimentServiceTest}'s "every collaborator mocked /
 * pure logic tested directly" structure where the unit under test has no collaborators at all.
 */
class HandoffChainTest {

    private static final UUID ROOT = UUID.randomUUID();
    private static final UUID SPECIALIST_A = UUID.randomUUID();
    private static final UUID SPECIALIST_B = UUID.randomUUID();
    private static final UUID SPECIALIST_C = UUID.randomUUID();
    private static final UUID SPECIALIST_D = UUID.randomUUID();

    @Test
    void rootChainStartsAtDepthZeroWithOnlyItself() {
        HandoffChain chain = HandoffChain.root("session-1", ROOT);

        assertEquals(0, chain.depth());
        assertEquals(ROOT, chain.currentAgentId());
        assertFalse(chain.isAtMaxDepth());
        assertTrue(chain.wouldCycle(ROOT), "the root agent is already on its own chain");
        assertFalse(chain.wouldCycle(SPECIALIST_A));
    }

    @Test
    void nextIncrementsDepthAndAppendsToVisitedPath() {
        HandoffChain chain = HandoffChain.root("session-1", ROOT).next(SPECIALIST_A);

        assertEquals(1, chain.depth());
        assertEquals(SPECIALIST_A, chain.currentAgentId());
        assertEquals(java.util.List.of(ROOT, SPECIALIST_A), chain.visitedAgentIds());
    }

    @Test
    void aCannotDelegateBackToItselfThroughASpecialist() {
        // A -> B, B tries to delegate back to A: this is exactly the cycle the build task asks
        // for ("A can't delegate back to A").
        HandoffChain afterHopToB = HandoffChain.root("session-1", ROOT).next(SPECIALIST_A);

        assertTrue(afterHopToB.wouldCycle(ROOT));
    }

    @Test
    void aLongerCycleThroughSeveralHopsIsStillDetected() {
        // A -> B -> C, C tries to delegate back to B (not just back to the root A).
        HandoffChain afterTwoHops = HandoffChain.root("session-1", ROOT)
            .next(SPECIALIST_A)
            .next(SPECIALIST_B);

        assertTrue(afterTwoHops.wouldCycle(SPECIALIST_A));
        assertFalse(afterTwoHops.wouldCycle(SPECIALIST_C), "an agent never before seen on this chain is not a cycle");
    }

    @Test
    void maxDepthStopsTheChainRegardlessOfCycles() {
        HandoffChain chain = HandoffChain.root("session-1", ROOT);
        for (int i = 0; i < HandoffChain.MAX_DEPTH; i++) {
            assertFalse(chain.isAtMaxDepth(), "should still have room at depth " + chain.depth());
            chain = chain.next(UUID.randomUUID());
        }

        assertEquals(HandoffChain.MAX_DEPTH, chain.depth());
        assertTrue(chain.isAtMaxDepth());
    }

    @Test
    void nextNeverMutatesTheOriginalChain() {
        HandoffChain original = HandoffChain.root("session-1", ROOT);
        HandoffChain extended = original.next(SPECIALIST_A);

        assertEquals(0, original.depth());
        assertEquals(java.util.List.of(ROOT), original.visitedAgentIds());
        assertEquals(1, extended.depth());
    }

    @Test
    void describeVisitedRendersTheFullPathInOrder() {
        HandoffChain chain = HandoffChain.root("session-1", ROOT).next(SPECIALIST_A).next(SPECIALIST_D);

        String description = chain.describeVisited();

        assertEquals(ROOT + " -> " + SPECIALIST_A + " -> " + SPECIALIST_D, description);
    }
}
