package com.agenticai.core.quality.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Known-answer tests for the Wilson score interval.
 *
 * <p>The expected bounds below are the published textbook values for these
 * inputs, not values produced by this implementation and then frozen. That
 * distinction is the entire value of the test: it pins the FORMULA, so a future
 * "simplification" to the Wald interval (which is what most people reach for)
 * fails here instead of silently narrowing every interval the console prints.
 */
class WilsonIntervalTest {

    private static final double TOLERANCE = 1e-3;

    @Test
    void textbookCase8of10() {
        // Wilson 95% interval for 8/10 is approximately [0.490, 0.943].
        ConfidenceInterval interval = WilsonInterval.forProportion(8, 10);

        assertEquals(0.4902, interval.low(), TOLERANCE);
        assertEquals(0.9433, interval.high(), TOLERANCE);
        assertEquals(0.95, interval.confidenceLevel());
        assertEquals(IntervalMethod.WILSON_SCORE, interval.method());
        assertTrue(interval.contains(0.8), "the interval must contain the observed proportion 8/10");
    }

    @Test
    void textbookCase50of100IsSymmetricAboutOneHalf() {
        // Wilson 95% interval for 50/100 is approximately [0.4038, 0.5962].
        // At p=0.5 the shrinkage term cancels exactly, so the interval is
        // symmetric about 0.5 - a property, not a coincidence, and a cheap
        // check that the centre formula is right.
        ConfidenceInterval interval = WilsonInterval.forProportion(50, 100);

        assertEquals(0.4038, interval.low(), TOLERANCE);
        assertEquals(0.5962, interval.high(), TOLERANCE);
        assertEquals(1.0, interval.low() + interval.high(), 1e-12);
    }

    /**
     * The reason this class exists rather than a two-line Wald helper: at a
     * perfect score, Wald's width collapses to zero and would let the console
     * print "100% +/- 0" from ten rollouts.
     */
    @Test
    void perfectScoreStillHasWidth() {
        // Wilson 95% interval for 10/10 is approximately [0.7225, 1.0].
        ConfidenceInterval interval = WilsonInterval.forProportion(10, 10);

        assertEquals(0.7225, interval.low(), TOLERANCE);
        assertEquals(1.0, interval.high(), 1e-12);
        assertTrue(interval.width() > 0.25,
            "a perfect 10/10 must still carry real uncertainty; Wald would report zero width");
    }

    @Test
    void zeroScoreStillHasWidthAndStaysInsideUnitRange() {
        // Wilson 95% interval for 0/10 is approximately [0.0, 0.2775].
        ConfidenceInterval interval = WilsonInterval.forProportion(0, 10);

        assertEquals(0.0, interval.low(), 1e-12);
        assertEquals(0.2775, interval.high(), TOLERANCE);
    }

    @Test
    void moreTrialsNarrowTheIntervalAtTheSameProportion() {
        ConfidenceInterval small = WilsonInterval.forProportion(4, 5);
        ConfidenceInterval large = WilsonInterval.forProportion(80, 100);

        assertTrue(large.width() < small.width(),
            "80/100 must be a tighter claim than 4/5 - this is what replication buys");
    }

    @Test
    void higherConfidenceWidensTheInterval() {
        ConfidenceInterval ninety = WilsonInterval.forProportion(8, 10, 0.90);
        ConfidenceInterval ninetyNine = WilsonInterval.forProportion(8, 10, 0.99);

        assertTrue(ninetyNine.width() > ninety.width());
        assertEquals(0.90, ninety.confidenceLevel());
        assertEquals(0.99, ninetyNine.confidenceLevel());
    }

    @Test
    void tabledQuantilesAreTheStandardNormalValues() {
        assertEquals(1.6448536269514722, WilsonInterval.zFor(0.90), 1e-12);
        assertEquals(1.959963984540054, WilsonInterval.zFor(0.95), 1e-12);
        assertEquals(2.5758293035489004, WilsonInterval.zFor(0.99), 1e-12);
    }

    @Test
    void untabledConfidenceLevelIsRefusedRatherThanApproximated() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class, () -> WilsonInterval.forProportion(8, 10, 0.973));

        assertTrue(thrown.getMessage().contains("0.973"));
    }

    @Test
    void zeroTrialsIsAnAbsentMeasurementNotAProportion() {
        assertThrows(IllegalArgumentException.class, () -> WilsonInterval.forProportion(0, 0));
    }

    @Test
    void successesCannotExceedTrials() {
        assertThrows(IllegalArgumentException.class, () -> WilsonInterval.forProportion(11, 10));
        assertThrows(IllegalArgumentException.class, () -> WilsonInterval.forProportion(-1, 10));
    }
}
