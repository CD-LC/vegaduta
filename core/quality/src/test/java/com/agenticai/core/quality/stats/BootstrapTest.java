package com.agenticai.core.quality.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the percentile and hierarchical bootstraps.
 *
 * <p>A resampling estimator cannot be pinned by one hand-computed number, so
 * these tests pin it three other ways, each of which would catch a real bug:
 * <ul>
 *   <li><strong>Degenerate data</strong> - a sample with no spread must produce
 *       an interval of exactly zero width. Any indexing or averaging error shows
 *       up immediately.</li>
 *   <li><strong>Agreement with theory where theory applies</strong> - on a large,
 *       symmetric sample the percentile interval must land close to the
 *       normal-theory interval {@code mean +/- z*SE}. This is the test that would
 *       catch a bootstrap that resampled the wrong number of observations (which
 *       scales the width by a factor nobody would notice by eye).</li>
 *   <li><strong>The documented nesting claim</strong> - on the same nested data,
 *       the hierarchical interval must come out substantially WIDER than the flat
 *       one. That claim is the reason the two-level version exists, and it is
 *       asserted here rather than only asserted in a comment.</li>
 * </ul>
 */
class BootstrapTest {

    private static final double LEVEL = 0.95;
    private static final long SEED = 20260911L;

    // ---- percentile definition ----

    @Test
    void percentileUsesTypeSevenLinearInterpolation() {
        double[] sorted = {1.0, 2.0, 3.0, 4.0, 5.0};

        assertEquals(1.0, Bootstrap.percentile(sorted, 0.0), 1e-12);
        assertEquals(5.0, Bootstrap.percentile(sorted, 1.0), 1e-12);
        assertEquals(3.0, Bootstrap.percentile(sorted, 0.5), 1e-12);
        // position = 0.25 * (5-1) = 1.0 -> exactly the second element
        assertEquals(2.0, Bootstrap.percentile(sorted, 0.25), 1e-12);
        // position = 0.1 * 4 = 0.4 -> 1.0 + 0.4*(2.0-1.0) = 1.4
        assertEquals(1.4, Bootstrap.percentile(sorted, 0.1), 1e-12);
    }

    @Test
    void percentileOfASingleValueIsThatValue() {
        assertEquals(7.0, Bootstrap.percentile(new double[] {7.0}, 0.025), 1e-12);
    }

    // ---- degenerate data ----

    @Test
    void aSampleWithNoSpreadProducesAZeroWidthInterval() {
        double[] identical = {0.5, 0.5, 0.5, 0.5, 0.5};

        ConfidenceInterval interval = Bootstrap.forMean(identical, 500, LEVEL, SEED);

        assertEquals(0.5, interval.low(), 1e-12);
        assertEquals(0.5, interval.high(), 1e-12);
        assertEquals(IntervalMethod.PERCENTILE_BOOTSTRAP, interval.method());
    }

    @Test
    void hierarchicalBootstrapOnIdenticalCasesIsAlsoZeroWidth() {
        List<CaseMeasurements> cases = List.of(
            new CaseMeasurements("a", List.of(0.4, 0.4, 0.4)),
            new CaseMeasurements("b", List.of(0.4, 0.4, 0.4)),
            new CaseMeasurements("c", List.of(0.4, 0.4, 0.4)));

        ConfidenceInterval interval =
            Bootstrap.hierarchicalMeanOfCaseMeans(cases, 500, LEVEL, SEED);

        assertEquals(0.4, interval.low(), 1e-12);
        assertEquals(0.4, interval.high(), 1e-12);
        assertEquals(IntervalMethod.HIERARCHICAL_BOOTSTRAP, interval.method());
    }

    // ---- agreement with normal theory on a large symmetric sample ----

    @Test
    void flatBootstrapAgreesWithNormalTheoryOnALargeSymmetricSample() {
        // 100 points on an even grid 0.00 .. 0.99: mean 0.495, population sd
        // h*sqrt((n^2-1)/12) = 0.01*sqrt(833.25) = 0.28866, so
        // SE = 0.028866 and the normal 95% half-width is 1.96*SE = 0.05658.
        double[] grid = new double[100];
        for (int i = 0; i < grid.length; i++) {
            grid[i] = i / 100.0;
        }
        double normalHalfWidth = WilsonInterval.Z_95 * 0.0288660 /* SE */;

        ConfidenceInterval interval = Bootstrap.forMean(grid, 4000, LEVEL, SEED);
        double bootstrapHalfWidth = interval.width() / 2.0;

        assertTrue(interval.contains(0.495), "the interval must contain the sample mean");
        assertEquals(
            1.0,
            bootstrapHalfWidth / normalHalfWidth,
            0.2,
            "on a large symmetric sample the percentile bootstrap must land within 20% of the "
                + "normal-theory half-width (" + normalHalfWidth + "), got " + bootstrapHalfWidth);
    }

    // ---- the nesting claim ----

    @Test
    void hierarchicalIntervalIsSubstantiallyWiderThanTheFlatOneOnNestedData() {
        // Five cases whose DIFFICULTY differs a lot (means 0.1 .. 0.9) but whose
        // rollouts within a case barely vary (+/- 0.01). A flat bootstrap sees 25
        // "independent" observations and reports a tight interval; the truth is
        // that the suite covered five cases, and adding more rollouts of the same
        // five must not buy a narrower claim about the suite.
        List<CaseMeasurements> cases = new ArrayList<>();
        double[] caseMeans = {0.1, 0.3, 0.5, 0.7, 0.9};
        for (int c = 0; c < caseMeans.length; c++) {
            List<Double> rollouts = new ArrayList<>();
            for (int r = 0; r < 5; r++) {
                rollouts.add(caseMeans[c] + (r - 2) * 0.005);
            }
            cases.add(new CaseMeasurements("case-" + c, rollouts));
        }

        ConfidenceInterval flat = Bootstrap.forMean(Bootstrap.flatten(cases), 4000, LEVEL, SEED);
        ConfidenceInterval hierarchical =
            Bootstrap.hierarchicalMeanOfCaseMeans(cases, 4000, LEVEL, SEED);

        assertTrue(
            hierarchical.width() > flat.width() * 1.5,
            "the two-level interval must be much wider than the flat one on nested data - flat="
                + flat.width() + ", hierarchical=" + hierarchical.width());
        assertTrue(hierarchical.contains(0.5), "both must still cover the true suite mean");
        assertTrue(flat.contains(0.5));
    }

    @Test
    void meanOfCaseMeansWeightsCasesEquallyRegardlessOfRolloutCount() {
        // Case "a" was rerun ten times, case "b" once. The suite statistic must
        // still be the midpoint of the two case means (0.5), not dragged toward
        // "a" by its extra rollouts.
        List<Double> manyRollouts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            manyRollouts.add(0.2);
        }
        List<CaseMeasurements> cases = List.of(
            new CaseMeasurements("a", manyRollouts),
            new CaseMeasurements("b", List.of(0.8)));

        assertEquals(0.5, Bootstrap.meanOfCaseMeans(cases), 1e-12);
    }

    // ---- determinism ----

    @Test
    void theSameSeedGivesTheSameIntervalAndADifferentSeedDoesNot() {
        double[] sample = {0.1, 0.4, 0.45, 0.6, 0.62, 0.8, 0.9};

        ConfidenceInterval first = Bootstrap.forMean(sample, 1000, LEVEL, 42L);
        ConfidenceInterval same = Bootstrap.forMean(sample, 1000, LEVEL, 42L);
        ConfidenceInterval other = Bootstrap.forMean(sample, 1000, LEVEL, 43L);

        assertEquals(first, same, "a console reload must not change the reported interval");
        assertTrue(first.low() != other.low() || first.high() != other.high(),
            "a different resampling stream should generally produce a different interval");
    }

    // ---- guards ----

    @Test
    void oneObservationIsNotASampleOfSpread() {
        assertThrows(
            IllegalArgumentException.class, () -> Bootstrap.forMean(new double[] {0.5}, 500, LEVEL, SEED));
    }

    @Test
    void oneClusterCannotBeResampledAtTheClusterLevel() {
        List<CaseMeasurements> single =
            List.of(new CaseMeasurements("only", List.of(0.1, 0.2, 0.3)));

        assertThrows(
            IllegalArgumentException.class,
            () -> Bootstrap.hierarchicalMeanOfCaseMeans(single, 500, LEVEL, SEED));
    }

    @Test
    void tooFewResamplesIsRefused() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Bootstrap.forMean(new double[] {0.1, 0.2}, 10, LEVEL, SEED));
    }
}
