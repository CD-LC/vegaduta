package com.agenticai.core.agents.job.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentScheduleCadenceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    @Test
    void intervalNextIsFromNowPlusInterval() {
        AgentScheduleCadence c = AgentScheduleCadence.of(null, 300);
        assertTrue(c.isInterval());
        assertNull(c.cron());
        assertEquals(NOW.plusSeconds(300), c.nextAfter("UTC", NOW));
        assertEquals("every 300s", c.describe());
    }

    @Test
    void intervalGapEqualsInterval() {
        AgentScheduleCadence c = AgentScheduleCadence.of("  ", 3600);
        assertEquals(Duration.ofHours(1), c.gapAfter("Asia/Kolkata", NOW));
    }

    @Test
    void intervalBoundaryIsExactlyOneMinute() {
        assertEquals(60, AgentScheduleCadence.of(null, 60).intervalSeconds());
        assertThrows(IllegalArgumentException.class, () -> AgentScheduleCadence.of(null, 59));
        assertThrows(IllegalArgumentException.class, () -> AgentScheduleCadence.of(null, 0));
        assertThrows(IllegalArgumentException.class, () -> AgentScheduleCadence.of(null, -5));
        assertThrows(IllegalArgumentException.class,
            () -> AgentScheduleCadence.of(null, AgentScheduleCadence.MAX_INTERVAL_SECONDS + 1));
    }

    @Test
    void exactlyOneOfCronOrIntervalIsRequired() {
        IllegalArgumentException both = assertThrows(IllegalArgumentException.class,
            () -> AgentScheduleCadence.of("0 9 * * *", 300));
        assertTrue(both.getMessage().contains("both"));
        IllegalArgumentException neither = assertThrows(IllegalArgumentException.class,
            () -> AgentScheduleCadence.of(null, null));
        assertTrue(neither.getMessage().contains("neither"));
        assertThrows(IllegalArgumentException.class, () -> AgentScheduleCadence.of("   ", null));
    }

    @Test
    void cronIsNormalizedAndEvaluatedInTimezone() {
        AgentScheduleCadence c = AgentScheduleCadence.of("30 9 * * *", null);
        assertFalse(c.isInterval());
        assertEquals("0 30 9 * * *", c.cron());
        // 09:30 Asia/Kolkata (UTC+5:30) is 04:00 UTC
        assertEquals(Instant.parse("2026-09-26T04:00:00Z"), c.nextAfter("Asia/Kolkata", NOW));
    }

    @Test
    void unknownTimezoneIsRejectedForIntervalToo() {
        AgentScheduleCadence c = AgentScheduleCadence.of(null, 120);
        assertThrows(IllegalArgumentException.class, () -> c.nextAfter("Mars/Olympus", NOW));
    }

    @Test
    void storedRoundTrips() {
        AgentScheduleCadence c = AgentScheduleCadence.stored(null, 90);
        assertTrue(c.isInterval());
        assertEquals(NOW.plusSeconds(90), c.nextAfter(null, NOW));
    }
}
