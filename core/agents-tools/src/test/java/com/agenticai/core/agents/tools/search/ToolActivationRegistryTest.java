package com.agenticai.core.agents.tools.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolActivationRegistryTest {

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-04T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void recallsTheNewestNamesOldestFirstAndRespectsTheLimit() {
        ToolActivationRegistry registry = new ToolActivationRegistry();
        registry.remember("k", List.of("a", "b"), 10);
        registry.remember("k", List.of("c"), 10);

        assertEquals(List.of("a", "b", "c"), registry.recall("k", 10));
        assertEquals(List.of("b", "c"), registry.recall("k", 2));
    }

    @Test
    void rememberingAgainMovesANameToTheNewestEnd() {
        ToolActivationRegistry registry = new ToolActivationRegistry();
        registry.remember("k", List.of("a", "b", "c"), 10);
        registry.remember("k", List.of("a"), 10);

        assertEquals(List.of("b", "c", "a"), registry.recall("k", 10));
    }

    @Test
    void keepsOnlyTheNewestMaxNamesPerSession() {
        ToolActivationRegistry registry = new ToolActivationRegistry();
        registry.remember("k", List.of("a", "b", "c", "d"), 2);

        assertEquals(List.of("c", "d"), registry.recall("k", 10));
    }

    @Test
    void aNonPositiveMaxRemembersNothing() {
        ToolActivationRegistry registry = new ToolActivationRegistry();
        registry.remember("k", List.of("a"), 0);
        registry.remember("k", List.of("a"), -1);

        assertTrue(registry.recall("k", 10).isEmpty());
        assertEquals(0, registry.sessionCount());
    }

    @Test
    void sessionsExpireAfterTheTimeToLiveAndTouchingKeepsThemAlive() {
        MutableClock clock = new MutableClock();
        ToolActivationRegistry registry =
                new ToolActivationRegistry(10, Duration.ofMinutes(30), clock);
        registry.remember("idle", List.of("a"), 5);
        registry.remember("busy", List.of("b"), 5);

        clock.advance(Duration.ofMinutes(20));
        assertEquals(List.of("b"), registry.recall("busy", 5)); // touches busy
        clock.advance(Duration.ofMinutes(20));

        assertTrue(registry.recall("idle", 5).isEmpty());
        assertEquals(List.of("b"), registry.recall("busy", 5));
    }

    @Test
    void evictsTheLeastRecentlyUsedSessionBeyondTheCap() {
        ToolActivationRegistry registry =
                new ToolActivationRegistry(2, Duration.ofHours(1), new MutableClock());
        registry.remember("one", List.of("a"), 5);
        registry.remember("two", List.of("b"), 5);
        registry.recall("one", 5); // "two" is now the least recently used
        registry.remember("three", List.of("c"), 5);

        assertEquals(2, registry.sessionCount());
        assertEquals(List.of("a"), registry.recall("one", 5));
        assertTrue(registry.recall("two", 5).isEmpty());
        assertEquals(List.of("c"), registry.recall("three", 5));
    }

    @Test
    void keysIsolateTenantsAgentsAndSessions() {
        UUID agent = UUID.randomUUID();
        String tenantA = ToolActivationRegistry.key("tenant-a", agent, "s1");

        assertNotEquals(tenantA, ToolActivationRegistry.key("tenant-b", agent, "s1"));
        assertNotEquals(tenantA, ToolActivationRegistry.key("tenant-a", UUID.randomUUID(), "s1"));
        assertNotEquals(tenantA, ToolActivationRegistry.key("tenant-a", agent, "s2"));
        assertEquals("-|-|-", ToolActivationRegistry.key(null, null, null));

        ToolActivationRegistry registry = new ToolActivationRegistry();
        registry.remember(tenantA, List.of("secret_tool"), 5);
        assertTrue(
                registry.recall(ToolActivationRegistry.key("tenant-b", agent, "s1"), 5).isEmpty());
    }

    @Test
    void nullAndBlankInputsAreIgnored() {
        ToolActivationRegistry registry = new ToolActivationRegistry();
        registry.remember(null, List.of("a"), 5);
        registry.remember("k", null, 5);
        registry.remember("k", List.of(), 5);
        registry.remember("k", java.util.Arrays.asList("", null, "ok"), 5);

        assertEquals(List.of("ok"), registry.recall("k", 5));
        assertTrue(registry.recall(null, 5).isEmpty());
        assertTrue(registry.recall("k", 0).isEmpty());
    }
}
