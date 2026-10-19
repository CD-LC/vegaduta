package com.agenticai.core.security.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SingleUseTicketRegistryTest {

    private static final Instant T0 = Instant.parse("2026-10-17T00:00:00Z");

    @Test
    void aTicketIsAcceptedOnceAndRefusedAfterwards() {
        SingleUseTicketRegistry registry = new SingleUseTicketRegistry(Clock.fixed(T0, ZoneOffset.UTC), 10);
        long exp = T0.getEpochSecond() + 300;

        assertTrue(registry.tryConsume("ticket-a", exp));
        assertFalse(registry.tryConsume("ticket-a", exp), "replay");
        assertTrue(registry.tryConsume("ticket-b", exp), "a different ticket is independent");
    }

    @Test
    void blankTicketsAreNeverAccepted() {
        SingleUseTicketRegistry registry = new SingleUseTicketRegistry();

        assertFalse(registry.tryConsume(null, 1));
        assertFalse(registry.tryConsume("", 1));
    }

    @Test
    void theTableIsBoundedAndFailsClosedWhenFullOfLiveEntries() {
        SingleUseTicketRegistry registry = new SingleUseTicketRegistry(Clock.fixed(T0, ZoneOffset.UTC), 2);
        long exp = T0.getEpochSecond() + 300;

        assertTrue(registry.tryConsume("a", exp));
        assertTrue(registry.tryConsume("b", exp));
        assertFalse(registry.tryConsume("c", exp), "full of live entries: refuse, do not forget");
        assertEquals(2, registry.size());
    }

    @Test
    void expiredEntriesAreSweptToMakeRoom() {
        Instant[] now = {T0};
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        SingleUseTicketRegistry registry = new SingleUseTicketRegistry(clock, 2);

        assertTrue(registry.tryConsume("a", T0.getEpochSecond() + 60));
        assertTrue(registry.tryConsume("b", T0.getEpochSecond() + 60));
        now[0] = T0.plusSeconds(600);

        assertTrue(registry.tryConsume("c", now[0].getEpochSecond() + 60));
        assertEquals(1, registry.size());
    }

    @Test
    void expiredEntriesAreDroppedEvenWhenTheTableIsFarFromFull() {
        Instant[] now = {T0};
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        SingleUseTicketRegistry registry = new SingleUseTicketRegistry(clock, 100_000);
        assertTrue(registry.tryConsume("a", T0.getEpochSecond() + 60));
        assertTrue(registry.tryConsume("b", T0.getEpochSecond() + 60));
        assertEquals(2, registry.size());

        now[0] = T0.plusSeconds(600);
        assertTrue(registry.tryConsume("c", now[0].getEpochSecond() + 60));

        assertEquals(1, registry.size(), "the two expired entries are gone");
    }
}
