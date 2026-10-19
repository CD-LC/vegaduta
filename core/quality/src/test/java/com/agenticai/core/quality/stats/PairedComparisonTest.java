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
 * Paired A/B over constructed datasets with KNOWN verdicts.
 *
 * <p>Four of these tests exist to pin the four ways this can end, because the
 * failure mode being prevented is a console that only knows how to render a
 * winner. The fifth demonstrates the power claim from
 * {@link PairedComparison}'s javadoc - that pairing turns an inconclusive
 * comparison into a conclusive one on the same data - by computing both and
 * asserting they disagree.
 */
class PairedComparisonTest {

    private static final long SEED = 20260911L;
    private static final double LEVEL = 0.95;
    private static final int RESAMPLES = 2000;

    /** Eight cases of differing difficulty, with a constant per-case delta. */
    private static List<PairedCaseOutcomes> constantDelta(double delta) {
        double[] baselineDifficulty = {0.10, 0.22, 0.35, 0.44, 0.58, 0.63, 0.77, 0.85};
        List<PairedCaseOutcomes> pairs = new ArrayList<>();
        for (int i = 0; i < baselineDifficulty.length; i++) {
            pairs.add(PairedCaseOutcomes.of(
                "case-" + i, baselineDifficulty[i], baselineDifficulty[i] + delta));
        }
        return pairs;
    }

    @Test
    void aConsistentImprovementIsCalledAWinner() {
        PairedComparisonResult result =
            PairedComparison.compare(constantDelta(0.10), LEVEL, RESAMPLES, SEED, null);

        assertEquals(8, result.pairedCases());
        assertEquals(0.10, result.meanDelta(), 1e-12);
        // Every case moved the same way by the same amount, so every bootstrap
        // replicate is exactly +0.10 and the interval is degenerate there.
        assertNotNull(result.deltaInterval());
        assertEquals(0.10, result.deltaInterval().low(), 1e-12);
        assertEquals(0.10, result.deltaInterval().high(), 1e-12);
        assertEquals(IntervalMethod.PAIRED_BOOTSTRAP, result.deltaInterval().method());
        assertEquals(ComparisonVerdict.VARIANT_BETTER, result.verdict());
        assertTrue(result.namesAWinner());

        assertEquals(8, result.casesVariantBetter());
        assertEquals(0, result.casesBaselineBetter());
        assertEquals(0, result.casesTied());
        // Sign test on 8 discordant cases all one way: 2 * 0.5^8 = 0.0078125.
        assertEquals(0.0078125, result.signTestPValue(), 1e-12);
        assertNull(result.noiseFloorTested(),
            "no floor was supplied, and that absence must be reported rather than treated as a pass");
        assertTrue(result.explanation().contains("Variant better"));
    }

    @Test
    void aConsistentRegressionIsCalledTheOtherWay() {
        PairedComparisonResult result =
            PairedComparison.compare(constantDelta(-0.10), LEVEL, RESAMPLES, SEED, null);

        assertEquals(-0.10, result.meanDelta(), 1e-12);
        assertEquals(ComparisonVerdict.BASELINE_BETTER, result.verdict());
        assertEquals(8, result.casesBaselineBetter());
    }

    @Test
    void anInconsistentDifferenceIsCannotTellRatherThanATie() {
        // Four cases up 0.2, four cases down 0.2: mean delta 0, and the interval
        // must straddle zero.
        List<PairedCaseOutcomes> pairs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double delta = (i % 2 == 0) ? 0.2 : -0.2;
            pairs.add(PairedCaseOutcomes.of("case-" + i, 0.5, 0.5 + delta));
        }

        PairedComparisonResult result = PairedComparison.compare(pairs, LEVEL, RESAMPLES, SEED, null);

        assertEquals(0.0, result.meanDelta(), 1e-12);
        assertTrue(result.deltaInterval().straddlesZero());
        assertEquals(ComparisonVerdict.CANNOT_TELL, result.verdict());
        assertFalse(result.namesAWinner());
        assertTrue(result.explanation().contains("Cannot tell"));
        assertEquals(4, result.casesVariantBetter());
        assertEquals(4, result.casesBaselineBetter());
        // 8 discordant, 4 each way: the sign test sees nothing at all.
        assertEquals(1.0, result.signTestPValue(), 1e-12);
    }

    /**
     * The verdict this package was built for: a statistically clean effect that
     * is smaller than the platform's own rerun-to-rerun variation.
     */
    @Test
    void aRealButTinyEffectIsReportedAsInsideTheNoiseFloor() {
        NoiseFloor floor = new NoiseFloor(
            8, 8, 8,
            0.040, 0.070,
            null,
            0.050,
            0.001, null,
            false);

        PairedComparisonResult result =
            PairedComparison.compare(constantDelta(0.01), LEVEL, RESAMPLES, SEED, floor);

        // The interval is degenerate at +0.01 and excludes zero, so this is a
        // "significant" effect by the interval alone...
        assertTrue(result.deltaInterval().excludesZero());
        // ...and it is still not reportable, because rerunning the unchanged
        // configuration moves things by up to 0.05.
        assertEquals(ComparisonVerdict.INSIDE_NOISE_FLOOR, result.verdict());
        assertFalse(result.namesAWinner());
        assertEquals(0.050, result.noiseFloorTested(), 1e-12);
        assertTrue(result.explanation().contains("noise floor"));
    }

    @Test
    void anEffectThatClearsTheFloorIsStillAWinner() {
        NoiseFloor floor = new NoiseFloor(
            8, 8, 8, 0.010, 0.020, null, 0.020, 0.000, null, false);

        PairedComparisonResult result =
            PairedComparison.compare(constantDelta(0.10), LEVEL, RESAMPLES, SEED, floor);

        assertEquals(ComparisonVerdict.VARIANT_BETTER, result.verdict());
        assertEquals(0.020, result.noiseFloorTested(), 1e-12);
    }

    @Test
    void oneCaseIsInsufficientDataNotAResult() {
        PairedComparisonResult result = PairedComparison.compare(
            List.of(PairedCaseOutcomes.of("only", 0.2, 0.9)), LEVEL, RESAMPLES, SEED, null);

        assertEquals(ComparisonVerdict.INSUFFICIENT_DATA, result.verdict());
        assertNull(result.deltaInterval(),
            "one pair cannot be resampled - the absence must be explicit");
        assertEquals(0.7, result.meanDelta(), 1e-12);
        assertFalse(result.namesAWinner());
    }

    /**
     * The power claim, asserted rather than merely documented: on data where case
     * difficulty dominates, the two arms' own intervals OVERLAP (so an eyeball
     * comparison of two error bars says "cannot tell") while the paired interval
     * cleanly excludes zero.
     */
    @Test
    void pairingResolvesAComparisonThatUnpairedIntervalsCannot() {
        List<PairedCaseOutcomes> pairs = constantDelta(0.05);

        double[] baselineArm = new double[pairs.size()];
        double[] variantArm = new double[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            baselineArm[i] = pairs.get(i).baselineMean();
            variantArm[i] = pairs.get(i).variantMean();
        }
        ConfidenceInterval unpairedBaseline = Bootstrap.forMean(baselineArm, RESAMPLES, LEVEL, SEED);
        ConfidenceInterval unpairedVariant = Bootstrap.forMean(variantArm, RESAMPLES, LEVEL, SEED);

        PairedComparisonResult paired =
            PairedComparison.compare(pairs, LEVEL, RESAMPLES, SEED, null);

        assertTrue(
            unpairedBaseline.high() > unpairedVariant.low(),
            "the two arms' unpaired intervals must overlap on this data - that is the weak test");
        assertTrue(
            paired.deltaInterval().excludesZero(),
            "the paired interval must resolve the same data, because case difficulty cancels");
        assertEquals(ComparisonVerdict.VARIANT_BETTER, paired.verdict());
    }

    // ---- the exact sign test, on hand-computed values ----

    @Test
    void signTestMatchesTheExactBinomialTail() {
        // b=10, c=2 -> n=12 discordant; lower tail P(X<=2) = (1+12+66)/4096
        // = 79/4096; two-sided p = 158/4096 = 0.038574...
        assertEquals(158.0 / 4096.0, PairedComparison.signTestPValue(10, 2), 1e-12);

        // All five discordant cases one way: 2 * 0.5^5 = 0.0625.
        assertEquals(0.0625, PairedComparison.signTestPValue(0, 5), 1e-12);

        // A perfectly even split can never be evidence: p is capped at 1.
        assertEquals(1.0, PairedComparison.signTestPValue(3, 3), 1e-12);
    }

    @Test
    void signTestIsAbsentWhenNothingMoved() {
        assertNull(PairedComparison.signTestPValue(0, 0),
            "with no discordant cases there is nothing to test, and null says so");
    }

    @Test
    void signTestIsAbsentRatherThanFabricatedWhenItWouldUnderflow() {
        assertNull(
            PairedComparison.signTestPValue(
                PairedComparison.MAX_DISCORDANT_FOR_EXACT_SIGN_TEST + 1, 0));
    }

    // ---- guards ----

    @Test
    void anEmptyComparisonIsRefused() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PairedComparison.compare(List.of(), LEVEL, RESAMPLES, SEED, null));
    }

    @Test
    void aHalfPairedCaseIsRefusedAtConstruction() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new PairedCaseOutcomes("a", List.of(0.5), List.of()));
        assertThrows(
            IllegalArgumentException.class,
            () -> new PairedCaseOutcomes("a", null, List.of(0.5)));
    }

    @Test
    void raggedRolloutCountsAcrossArmsAreAllowed() {
        // Reconstructed repeats are ragged in practice; refusing them would mean
        // discarding real data.
        PairedCaseOutcomes pair =
            new PairedCaseOutcomes("a", List.of(0.4, 0.6), List.of(0.7, 0.8, 0.9));

        assertEquals(0.5, pair.baselineMean(), 1e-12);
        assertEquals(0.8, pair.variantMean(), 1e-12);
        assertEquals(0.3, pair.delta(), 1e-12);
    }
}
