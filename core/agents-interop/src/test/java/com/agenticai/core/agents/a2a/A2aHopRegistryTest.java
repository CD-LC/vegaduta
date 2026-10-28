package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.a2a.A2aInboundGuardTest.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class A2aHopRegistryTest {

    private MutableClock clock;
    private A2aHopRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-28T12:00:00Z"));
        registry = new A2aHopRegistry(clock);
    }

    @Test
    void lookupReturnsWhatWasRegisteredUntilReleased() {
        registry.register("s1", new A2aCallContext(2, "trace", true));
        assertEquals(2, registry.lookup("s1").orElseThrow().hop());
        registry.release("s1");
        assertTrue(registry.lookup("s1").isEmpty());
    }

    @Test
    void concurrentRequestsOnOneSessionKeepTheHigherHopAndShareTheEntry() {
        registry.register("s1", new A2aCallContext(3, "a", true));
        registry.register("s1", new A2aCallContext(0, "b", false));
        assertEquals(3, registry.lookup("s1").orElseThrow().hop(), "a later request cannot lower the hop");

        registry.release("s1");
        assertTrue(registry.lookup("s1").isPresent(), "still held by the other request");
        registry.release("s1");
        assertTrue(registry.lookup("s1").isEmpty());
    }

    @Test
    void entriesExpire() {
        registry.register("s1", new A2aCallContext(1, "t", false));
        clock.advance(A2aHopRegistry.ENTRY_TTL.plusSeconds(1));
        assertTrue(registry.lookup("s1").isEmpty());
    }

    @Test
    void aFullRegistryRefusesNewSessionsInsteadOfDroppingTheCounter() {
        for (int i = 0; i < A2aHopRegistry.MAX_ENTRIES; i++) {
            assertTrue(registry.register("s" + i, new A2aCallContext(1, "t", false)));
        }
        assertFalse(registry.register("overflow", new A2aCallContext(1, "t", false)));
        // An existing session can still register (it does not add an entry).
        assertTrue(registry.register("s0", new A2aCallContext(2, "t", false)));
        // Expired entries are reclaimed.
        clock.advance(A2aHopRegistry.ENTRY_TTL.plus(Duration.ofSeconds(1)));
        assertTrue(registry.register("overflow", new A2aCallContext(1, "t", false)));
    }

    @Test
    void unknownSessionHasNoContext() {
        assertTrue(registry.lookup("nope").isEmpty());
        assertTrue(registry.lookup(null).isEmpty());
    }
}
