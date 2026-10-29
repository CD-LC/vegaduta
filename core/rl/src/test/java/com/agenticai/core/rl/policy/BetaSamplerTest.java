package com.agenticai.core.rl.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link BetaSampler} is the whole randomness of Thompson sampling: {@code sampleBeta} must stay
 * inside [0,1] for every legal posterior shape, reproduce exactly under the same seed (the service
 * is deterministic given the same RNG), and concentrate around the Beta mean {@code
 * alpha/(alpha+beta)} as draws accumulate - a sampler skewed toward one arm would silently corrupt
 * the explore/exploit balance.
 */
class BetaSamplerTest {

    @Test
    void everyDrawStaysInsideTheUnitIntervalAcrossPosteriorShapes() {
        Random rng = new Random(42);
        for (double[] ab : new double[][] {
            {1.0, 1.0}, {0.5, 0.5}, {0.1, 5.0}, {8.0, 2.0}, {30.0, 30.0}
        }) {
            for (int i = 0; i < 200; i++) {
                double s = BetaSampler.sampleBeta(ab[0], ab[1], rng);
                assertTrue(s >= 0.0 && s <= 1.0,
                    "Beta(" + ab[0] + ", " + ab[1] + ") sampled " + s);
            }
        }
    }

    @Test
    void theSameSeedReproducesTheSameSequence() {
        Random a = new Random(7);
        Random b = new Random(7);
        for (int i = 0; i < 50; i++) {
            assertEquals(BetaSampler.sampleBeta(2.0, 3.0, a),
                BetaSampler.sampleBeta(2.0, 3.0, b), 0.0);
        }
    }

    @Test
    void theSamplingMeanConvergesToTheBetaMean() {
        Random rng = new Random(123);
        double alpha = 3.0, beta = 6.0;
        double sum = 0.0;
        int n = 40_000;
        for (int i = 0; i < n; i++) {
            sum += BetaSampler.sampleBeta(alpha, beta, rng);
        }
        double expected = alpha / (alpha + beta); // 1/3
        assertEquals(expected, sum / n, 0.01);
    }

    @Test
    void aDominantPosteriorSamplesNearOne() {
        // alpha >> beta: the distribution piles up at the top end, so almost every draw is high.
        Random rng = new Random(99);
        int high = 0;
        for (int i = 0; i < 1_000; i++) {
            if (BetaSampler.sampleBeta(50.0, 2.0, rng) > 0.8) {
                high++;
            }
        }
        assertTrue(high > 900, "Beta(50,2) should draw > 0.8 nearly always, got " + high);
    }

    @Test
    void aZeroShapeIsDegenerateNotAnException() {
        Random rng = new Random(1);
        // alpha == 0 -> Gamma(0) is 0, so the draw is exactly 0.0 (losing arm with no successes).
        assertEquals(0.0, BetaSampler.sampleBeta(0.0, 1.0, rng));
    }
}
