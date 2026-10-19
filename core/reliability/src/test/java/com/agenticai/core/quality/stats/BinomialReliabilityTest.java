package com.agenticai.core.quality.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Hand-computed known answers for pass@k and pass^k.
 *
 * <p>Every expected value below is worked out in the test's own comment from the
 * combinatorial definition, so the test verifies the identity of the estimator
 * rather than merely freezing whatever the code currently returns. The two
 * estimators are easy to swap by accident - they differ by one character in the
 * formula and by everything in meaning - and this is where that mistake gets
 * caught.
 */
class BinomialReliabilityTest {

    private static final double TOLERANCE = 1e-12;

    // ---- pass@k: the hiring question ----

    @Test
    void passAtKMatchesChenEstimator() {
        // n=5, c=1, k=2: 1 - C(4,2)/C(5,2) = 1 - 6/10 = 0.4
        assertEquals(0.4, BinomialReliability.passAtK(5, 1, 2), TOLERANCE);

        // n=10, c=2, k=3: 1 - C(8,3)/C(10,3) = 1 - 56/120 = 0.5333...
        assertEquals(1.0 - 56.0 / 120.0, BinomialReliability.passAtK(10, 2, 3), TOLERANCE);
    }

    @Test
    void passAtKIsOneWhenFewerFailuresThanDraws() {
        // n=5, c=4 leaves only 1 failure, so any 2 drawn rollouts include a
        // success: the estimator is exactly 1.
        assertEquals(1.0, BinomialReliability.passAtK(5, 4, 2), TOLERANCE);
    }

    @Test
    void passAtKIsZeroWhenNothingEverPassed() {
        assertEquals(0.0, BinomialReliability.passAtK(5, 0, 3), TOLERANCE);
    }

    @Test
    void passAtKRisesWithK() {
        // n=10, c=3. k=1: 0.3. k=2: 1 - (7/10)(6/9) = 1 - 0.46667 = 0.53333.
        // k=3: 1 - (7/10)(6/9)(5/8) = 1 - 0.291667 = 0.708333.
        assertEquals(0.3, BinomialReliability.passAtK(10, 3, 1), TOLERANCE);
        assertEquals(1.0 - (7.0 / 10) * (6.0 / 9), BinomialReliability.passAtK(10, 3, 2), TOLERANCE);
        assertEquals(
            1.0 - (7.0 / 10) * (6.0 / 9) * (5.0 / 8),
            BinomialReliability.passAtK(10, 3, 3),
            TOLERANCE);
    }

    // ---- pass^k: the operations question ----

    @Test
    void passHatKMatchesTauBenchEstimator() {
        // n=5, c=4, k=2: C(4,2)/C(5,2) = 6/10 = 0.6, equivalently (4/5)*(3/4).
        assertEquals(0.6, BinomialReliability.passHatK(5, 4, 2), TOLERANCE);

        // n=10, c=8, k=3: (8/10)(7/9)(6/8) = 0.4666...
        assertEquals(
            (8.0 / 10) * (7.0 / 9) * (6.0 / 8),
            BinomialReliability.passHatK(10, 8, 3),
            TOLERANCE);
    }

    @Test
    void passHatKIsOneOnlyWhenEveryRolloutPassed() {
        assertEquals(1.0, BinomialReliability.passHatK(5, 5, 5), TOLERANCE);
        // One failure in five: it is impossible to draw 5 of 5 all-passing.
        assertEquals(0.0, BinomialReliability.passHatK(5, 4, 5), TOLERANCE);
    }

    @Test
    void passHatKFallsWithK() {
        // n=10, c=5. k=1: 0.5. k=2: (5/10)(4/9) = 0.2222. k=3: *(3/8) = 0.08333.
        assertEquals(0.5, BinomialReliability.passHatK(10, 5, 1), TOLERANCE);
        assertEquals((5.0 / 10) * (4.0 / 9), BinomialReliability.passHatK(10, 5, 2), TOLERANCE);
        assertEquals(
            (5.0 / 10) * (4.0 / 9) * (3.0 / 8),
            BinomialReliability.passHatK(10, 5, 3),
            TOLERANCE);
    }

    @Test
    void atKOfOneBothEstimatorsCollapseToThePassRate() {
        // This is the whole content of the "unreplicated" badge: with one
        // rollout there is no reliability information, and both headline
        // reliability metrics degenerate to the plain rate.
        assertEquals(0.6, BinomialReliability.passAtK(5, 3, 1), TOLERANCE);
        assertEquals(0.6, BinomialReliability.passHatK(5, 3, 1), TOLERANCE);
        assertEquals(0.6, BinomialReliability.passAtOne(5, 3), TOLERANCE);
    }

    // ---- the teaching gap, from a known rate ----

    @Test
    void theGapAtNinetyPercentIsTheWholeArgument() {
        // The number the curriculum opens with: same agent, p=0.9.
        // pass@5 = 1 - 0.1^5 = 0.99999; pass^5 = 0.9^5 = 0.59049.
        assertEquals(0.99999, BinomialReliability.passAtKFromRate(0.9, 5), 1e-9);
        assertEquals(0.59049, BinomialReliability.passHatKFromRate(0.9, 5), 1e-9);
        assertTrue(
            BinomialReliability.passAtKFromRate(0.9, 5)
                - BinomialReliability.passHatKFromRate(0.9, 5) > 0.4,
            "the pass@k / pass^k gap at p=0.9, k=5 must exceed 0.4 - only one of those numbers is "
                + "honest for unattended production");
    }

    @Test
    void rateFormsAgreeWithTheEstimatorsAtKOfOne() {
        assertEquals(0.7, BinomialReliability.passAtKFromRate(0.7, 1), TOLERANCE);
        assertEquals(0.7, BinomialReliability.passHatKFromRate(0.7, 1), TOLERANCE);
    }

    // ---- guards ----

    @Test
    void kBeyondTheObservedRolloutsIsRefused() {
        // Reporting pass^10 from 5 observed rollouts would require exactly the
        // modelling assumption these estimators exist to avoid.
        assertThrows(IllegalArgumentException.class, () -> BinomialReliability.passHatK(5, 5, 10));
        assertThrows(IllegalArgumentException.class, () -> BinomialReliability.passAtK(5, 5, 10));
    }

    @Test
    void zeroRolloutsIsAnAbsentMeasurement() {
        assertThrows(IllegalArgumentException.class, () -> BinomialReliability.passAtOne(0, 0));
        assertThrows(IllegalArgumentException.class, () -> BinomialReliability.passHatK(0, 0, 1));
    }

    @Test
    void impossibleCountsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> BinomialReliability.passAtOne(5, 6));
        assertThrows(IllegalArgumentException.class, () -> BinomialReliability.passHatK(5, 5, 0));
        assertThrows(IllegalArgumentException.class, () -> BinomialReliability.passHatKFromRate(1.5, 2));
    }
}
