package com.agenticai.core.platform.abuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The honest-absence contract of the scorer: an unavailable signal is marked and excluded from the
 * aggregate, never counted as a fabricated zero; and a normalised score is clamped to 0..1 so a
 * signal cannot vote outside its weight. The tier ordering (least to most severe) is pinned too - a
 * reordered enum would silently flip every {@code compareTo} in the sweep.
 */
class AbuseSignalTest {

    @Test
    void anUnavailableSignalCarriesNoScore() {
        AbuseSignal signal = AbuseSignal.unavailable("geo-velocity", 0.20, "no IP source");

        assertEquals("geo-velocity", signal.name());
        assertFalse(signal.available());
        assertEquals(0.0, signal.rawValue());
        assertEquals(0.0, signal.normalizedScore());
        assertEquals(0.20, signal.weight());
        assertEquals("no IP source", signal.detail());
    }

    @Test
    void aComputedSignalIsClampedIntoUnitRange() {
        AbuseSignal hot = AbuseSignal.of("spend-spike", 12.0, 1.7, 0.30, "12x baseline");
        AbuseSignal cold = AbuseSignal.of("spend-spike", 0.1, -0.4, 0.30, "quiet");

        assertTrue(hot.available());
        assertEquals(1.0, hot.normalizedScore(), "scores cannot exceed 1.0");
        assertEquals(0.0, cold.normalizedScore(), "scores cannot go negative");
        assertEquals(12.0, hot.rawValue(), "the raw measurement is kept for display");
    }

    @Test
    void theRiskScoreIsAPureReadModel() {
        UUID tenant = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-01T00:00:00Z");
        List<AbuseSignal> signals =
                List.of(
                        AbuseSignal.of("spend-spike", 5.0, 0.8, 0.3, "5x"),
                        AbuseSignal.unavailable("geo-velocity", 0.2, "no data"));

        AbuseRiskScore score =
                new AbuseRiskScore(
                        tenant, "Acme", "user-1", 62.5, AbuseActionTier.THROTTLE, signals, at);

        assertEquals(tenant, score.tenantId());
        assertEquals("Acme", score.tenantName());
        assertEquals("user-1", score.userId());
        assertEquals(62.5, score.score());
        assertEquals(AbuseActionTier.THROTTLE, score.tier());
        assertEquals(2, score.signals().size());
        assertEquals(at, score.computedAt());
    }

    @Test
    void tiersStayOrderedLeastToMostSevere() {
        assertTrue(AbuseActionTier.NORMAL.ordinal() < AbuseActionTier.WATCH.ordinal());
        assertTrue(AbuseActionTier.WATCH.ordinal() < AbuseActionTier.THROTTLE.ordinal());
        assertTrue(AbuseActionTier.THROTTLE.ordinal() < AbuseActionTier.SUSPEND.ordinal());
        assertTrue(AbuseActionTier.SUSPEND.compareTo(AbuseActionTier.NORMAL) > 0);
    }
}
