package com.agenticai.core.quality.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Noise-floor estimation over reruns whose per-case disagreements are chosen so
 * that the expected mean, maximum and sign are all hand-computable.
 *
 * <p>The two behaviours that matter operationally are pinned here: the floor is
 * CONSERVATIVE (it is the upper end of the interval, not the average
 * disagreement, so pure noise does not clear its own floor half the time), and a
 * pair of runs that differ SYSTEMATICALLY is flagged as suspect rather than
 * silently accepted as a clean control.
 */
class NoiseFloorEstimatorTest {

    private static final long SEED = 20260911L;
    private static final double LEVEL = 0.95;
    private static final int RESAMPLES = 2000;

    private static List<PairedCaseOutcomes> rerunWithDeltas(double... deltas) {
        List<PairedCaseOutcomes> pairs = new ArrayList<>();
        for (int i = 0; i < deltas.length; i++) {
            pairs.add(PairedCaseOutcomes.of("case-" + i, 0.5, 0.5 + deltas[i]));
        }
        return pairs;
    }

    @Test
    void symmetricDisagreementGivesTheHandComputedFloor() {
        // Per-case deltas +0.1, -0.1, +0.2, -0.2:
        //   mean |delta| = (0.1 + 0.1 + 0.2 + 0.2)/4 = 0.15
        //   max  |delta| = 0.2
        //   mean  delta  = 0  (this is a genuine rerun, nothing drifted)
        NoiseFloor floor = NoiseFloorEstimator.estimate(
            rerunWithDeltas(0.1, -0.1, 0.2, -0.2), LEVEL, RESAMPLES, SEED);

        assertEquals(4, floor.pairedCases());
        assertEquals(0.15, floor.meanAbsoluteDelta(), 1e-12);
        assertEquals(0.20, floor.maxAbsoluteDelta(), 1e-12);
        assertEquals(0.0, floor.signedMeanDelta(), 1e-12);
        assertFalse(floor.configurationSuspect(),
            "a symmetric rerun is exactly what an unchanged configuration looks like");
    }

    @Test
    void theFloorIsTheConservativeUpperEndNotTheAverageDisagreement() {
        NoiseFloor floor = NoiseFloorEstimator.estimate(
            rerunWithDeltas(0.1, -0.1, 0.2, -0.2), LEVEL, RESAMPLES, SEED);

        assertNotNull(floor.absoluteDeltaInterval());
        assertEquals(floor.absoluteDeltaInterval().high(), floor.floor(), 1e-12);
        assertTrue(floor.floor() > floor.meanAbsoluteDelta(),
            "using the mean would let roughly half of pure-noise reruns clear their own floor");
        // Resampling four values drawn from {0.1, 0.1, 0.2, 0.2}, the upper 95%
        // bound lands on the maximum attainable replicate mean, 0.2.
        assertEquals(0.20, floor.floor(), 1e-9);
    }

    @Test
    void clearsIsStrictAndDirectionless() {
        NoiseFloor floor = NoiseFloorEstimator.estimate(
            rerunWithDeltas(0.1, -0.1, 0.2, -0.2), LEVEL, RESAMPLES, SEED);

        assertTrue(floor.clears(0.30));
        assertTrue(floor.clears(-0.30), "the floor is a magnitude, so direction must not matter");
        assertFalse(floor.clears(0.10));
        assertFalse(floor.clears(floor.floor()), "an effect exactly at the floor is not above it");
    }

    /**
     * The self-check that catches "this was not actually a rerun": if the two
     * supposedly identical runs differ in one direction on every case, something
     * changed between them and this is drift evidence, not a noise floor.
     */
    @Test
    void systematicDisagreementIsFlaggedAsASuspectConfiguration() {
        NoiseFloor floor = NoiseFloorEstimator.estimate(
            rerunWithDeltas(0.2, 0.2, 0.2, 0.2, 0.2, 0.2, 0.2, 0.2), LEVEL, RESAMPLES, SEED);

        assertEquals(0.2, floor.meanAbsoluteDelta(), 1e-12);
        assertEquals(0.2, floor.signedMeanDelta(), 1e-12);
        assertNotNull(floor.signedDeltaInterval());
        assertTrue(floor.signedDeltaInterval().excludesZero());
        assertTrue(floor.configurationSuspect(),
            "eight cases all moving the same way is not random rerun variation");
    }

    @Test
    void aPerfectlyStableRerunHasAFloorOfZero() {
        NoiseFloor floor = NoiseFloorEstimator.estimate(
            rerunWithDeltas(0.0, 0.0, 0.0, 0.0), LEVEL, RESAMPLES, SEED);

        assertEquals(0.0, floor.meanAbsoluteDelta(), 1e-12);
        assertEquals(0.0, floor.floor(), 1e-12);
        assertFalse(floor.configurationSuspect());
        // A zero floor is only honest when it was actually MEASURED as zero -
        // which is why an unmeasured floor must be null, never zero.
        assertTrue(floor.clears(0.001));
    }

    @Test
    void oneCaseGivesACrudeFloorAndNoIntervals() {
        NoiseFloor floor = NoiseFloorEstimator.estimate(rerunWithDeltas(0.1), LEVEL, RESAMPLES, SEED);

        assertNull(floor.absoluteDeltaInterval(),
            "one case cannot be resampled at the case level");
        assertNull(floor.signedDeltaInterval());
        assertEquals(0.1, floor.floor(), 1e-12,
            "the fallback floor is the worst observed disagreement - crude, but conservative");
        assertFalse(floor.configurationSuspect(),
            "with no interval there is no evidence of systematic drift either way");
    }

    @Test
    void theSameSeedGivesTheSameFloor() {
        List<PairedCaseOutcomes> reruns = rerunWithDeltas(0.05, -0.02, 0.11, -0.07, 0.03);

        NoiseFloor first = NoiseFloorEstimator.estimate(reruns, LEVEL, RESAMPLES, 3L);
        NoiseFloor second = NoiseFloorEstimator.estimate(reruns, LEVEL, RESAMPLES, 3L);

        assertEquals(first, second);
    }

    @Test
    void anEmptyRerunIsRefusedRatherThanTreatedAsAZeroFloor() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> NoiseFloorEstimator.estimate(List.of(), LEVEL, RESAMPLES, SEED));

        assertTrue(thrown.getMessage().contains("UNKNOWN"));
    }

    @Test
    void aNegativeFloorCannotBeConstructed() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new NoiseFloor(4, 4, 4, 0.1, 0.2, null, -0.01, 0.0, null, false));
    }

    @Test
    void aFloorWithNoPairedCasesCannotBeConstructed() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new NoiseFloor(0, 0, 0, 0.0, 0.0, null, 0.0, 0.0, null, false));
    }
}
