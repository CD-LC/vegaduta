package com.agenticai.core.platform.abuse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The suite {@link AbuseScoreCalculator}'s own javadoc promises exists ("see
 * {@code AbuseScoreCalculatorTest}") but which was never actually written -
 * the calculator is THE pure math behind every automatic WATCH/THROTTLE/
 * SUSPEND decision, so a silent regression here either suspends innocent
 * tenants or lets a real abuser sail through. Every test is plain JUnit with
 * no Spring/DB/mocks, exactly the property the class was designed for.
 */
class AbuseScoreCalculatorTest {

    // -- normalizeRate ----------------------------------------------------

    @Test
    void normalizeRateIsLinearBelowThresholdAndClampsAtOne() {
        assertEquals(0.5, AbuseScoreCalculator.normalizeRate(50, 100), 1e-9);
        assertEquals(1.0, AbuseScoreCalculator.normalizeRate(100, 100), 1e-9);
        // Values past the threshold must saturate, not keep growing - an
        // unbounded normalized score would let one hot signal dominate the
        // weighted mean far beyond its configured weight.
        assertEquals(1.0, AbuseScoreCalculator.normalizeRate(1_000_000, 100), 1e-9);
    }

    @Test
    void normalizeRateTreatsNonPositiveThresholdAsDisabled() {
        // threshold <= 0 is the admin's "turn this signal off" knob; it must
        // yield 0 (benign), never a divide-by-zero Infinity/NaN.
        assertEquals(0.0, AbuseScoreCalculator.normalizeRate(500, 0), 1e-9);
        assertEquals(0.0, AbuseScoreCalculator.normalizeRate(500, -1), 1e-9);
    }

    @Test
    void normalizeRateTreatsNonPositiveValueAsBenign() {
        assertEquals(0.0, AbuseScoreCalculator.normalizeRate(0, 100), 1e-9);
        assertEquals(0.0, AbuseScoreCalculator.normalizeRate(-5, 100), 1e-9);
    }

    // -- normalizeSpike ---------------------------------------------------

    @Test
    void normalizeSpikeIsBenignAtOrBelowBaseline() {
        assertEquals(0.0, AbuseScoreCalculator.normalizeSpike(1.0, 3.0), 1e-9);
        assertEquals(0.0, AbuseScoreCalculator.normalizeSpike(0.5, 3.0), 1e-9);
    }

    @Test
    void normalizeSpikeIsLinearBetweenBaselineAndMultiplier() {
        // ratio 2.0 with multiplier 3.0: (2-1)/(3-1) = 0.5
        assertEquals(0.5, AbuseScoreCalculator.normalizeSpike(2.0, 3.0), 1e-9);
        assertEquals(1.0, AbuseScoreCalculator.normalizeSpike(3.0, 3.0), 1e-9);
        assertEquals(1.0, AbuseScoreCalculator.normalizeSpike(30.0, 3.0), 1e-9);
    }

    @Test
    void normalizeSpikeWithDegenerateMultiplierTreatsAnyExcessAsMaximal() {
        // multiplier <= 1 would make the denominator ~0; the documented
        // contract is "any excess is maximal" instead of an Infinity.
        assertEquals(1.0, AbuseScoreCalculator.normalizeSpike(1.01, 1.0), 1e-9);
        assertEquals(1.0, AbuseScoreCalculator.normalizeSpike(1.01, 0.5), 1e-9);
        // ...but at-or-below baseline still wins over the degenerate branch.
        assertEquals(0.0, AbuseScoreCalculator.normalizeSpike(1.0, 1.0), 1e-9);
    }

    // -- aggregate --------------------------------------------------------

    @Test
    void aggregateIsAWeightedMeanTimesOneHundred() {
        List<AbuseSignal> signals = List.of(
            AbuseSignal.of("a", 0, 1.0, 3.0, ""),
            AbuseSignal.of("b", 0, 0.0, 1.0, "")
        );
        // (1.0*3 + 0.0*1) / 4 = 0.75 -> 75.0
        assertEquals(75.0, AbuseScoreCalculator.aggregate(signals), 1e-9);
    }

    @Test
    void aggregateExcludesUnavailableSignalsFromNumeratorAndDenominator() {
        // The "degrade gracefully" contract: a missing signal must not dilute
        // the score toward 0 by contributing its weight with a fabricated 0.
        List<AbuseSignal> signals = List.of(
            AbuseSignal.of("present", 0, 0.8, 1.0, ""),
            AbuseSignal.unavailable("missing", 5.0, "no ip feed")
        );
        assertEquals(80.0, AbuseScoreCalculator.aggregate(signals), 1e-9);
    }

    @Test
    void aggregateIgnoresZeroOrNegativeWeights() {
        List<AbuseSignal> signals = List.of(
            AbuseSignal.of("weightless", 0, 1.0, 0.0, ""),
            AbuseSignal.of("negative", 0, 1.0, -2.0, ""),
            AbuseSignal.of("real", 0, 0.25, 1.0, "")
        );
        assertEquals(25.0, AbuseScoreCalculator.aggregate(signals), 1e-9);
    }

    @Test
    void aggregateFailsOpenToZeroWhenNothingIsAvailable() {
        assertEquals(0.0, AbuseScoreCalculator.aggregate(List.of()), 1e-9);
        assertEquals(0.0, AbuseScoreCalculator.aggregate(List.of(
            AbuseSignal.unavailable("a", 1.0, ""),
            AbuseSignal.unavailable("b", 2.0, "")
        )), 1e-9);
    }

    @Test
    void abuseSignalOfClampsNormalizedScoreDefensively() {
        // AbuseSignal.of is the factory every signal builder uses - if its
        // clamp regressed, a buggy builder could push the aggregate past 100.
        assertEquals(1.0, AbuseSignal.of("x", 0, 7.3, 1.0, "").normalizedScore(), 1e-9);
        assertEquals(0.0, AbuseSignal.of("x", 0, -0.5, 1.0, "").normalizedScore(), 1e-9);
    }

    // -- decideTier -------------------------------------------------------

    @Test
    void decideTierBoundariesAreInclusiveOnTheLowerEdge() {
        AbuseThresholds t = thresholds(30, 60, 90);
        assertEquals(AbuseActionTier.NORMAL, AbuseScoreCalculator.decideTier(29.999, t));
        assertEquals(AbuseActionTier.WATCH, AbuseScoreCalculator.decideTier(30, t));
        assertEquals(AbuseActionTier.THROTTLE, AbuseScoreCalculator.decideTier(60, t));
        assertEquals(AbuseActionTier.SUSPEND, AbuseScoreCalculator.decideTier(90, t));
        assertEquals(AbuseActionTier.SUSPEND, AbuseScoreCalculator.decideTier(100, t));
    }

    @Test
    void decideTierWithMisorderedThresholdsStillPicksTheMostSevereApplicableTier() {
        // The documented defensive contract: an admin who fat-fingers
        // suspend < throttle must not create a band where a HIGHER score
        // gets a LOWER tier. Checked most-severe-first, suspend wins.
        AbuseThresholds inverted = thresholds(80, 60, 40);
        assertEquals(AbuseActionTier.SUSPEND, AbuseScoreCalculator.decideTier(70, inverted));
        assertEquals(AbuseActionTier.NORMAL, AbuseScoreCalculator.decideTier(30, inverted));
    }

    /** Only the three tier boundaries matter to decideTier - everything else is inert filler. */
    private static AbuseThresholds thresholds(double watch, double throttle, double suspend) {
        return new AbuseThresholds(
            60, 1440,
            3.0, 1.0, 1.0,
            1000, 1.0,
            60, 1.0,
            10, 1.0,
            0.5, 20, 1.0,
            1.0,
            watch, throttle, suspend,
            60, false, true
        );
    }
}
