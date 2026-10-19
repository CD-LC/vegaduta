package com.agenticai.core.rl.policy;

import java.util.Random;

/**
 * Draws samples from a Beta(alpha, beta) distribution - the core of Thompson
 * sampling over each {@link com.agenticai.core.rl.domain.PolicyArm}'s posterior.
 *
 * <p>Beta is sampled as {@code X / (X + Y)} where {@code X ~ Gamma(alpha, 1)}
 * and {@code Y ~ Gamma(beta, 1)}, using the Marsaglia-Tsang method for the
 * Gamma draws. No third-party dependency - a few lines against
 * {@link java.util.Random} is all Thompson sampling needs here.
 */
final class BetaSampler {

    private BetaSampler() {
    }

    static double sampleBeta(double alpha, double beta, Random rng) {
        double x = sampleGamma(alpha, rng);
        double y = sampleGamma(beta, rng);
        double sum = x + y;
        // Degenerate guard: both draws underflowed to 0 - fall back to the mean.
        return sum <= 0.0 ? alpha / (alpha + beta) : x / sum;
    }

    /** Marsaglia-Tsang Gamma(shape, scale=1) sampler. Handles shape &lt; 1 via the standard boost. */
    private static double sampleGamma(double shape, Random rng) {
        double k = shape;
        if (k <= 0.0) {
            return 0.0;
        }
        if (k < 1.0) {
            // Boost: Gamma(k) = Gamma(k+1) * U^(1/k)
            double u = rng.nextDouble();
            return sampleGamma(k + 1.0, rng) * Math.pow(u, 1.0 / k);
        }
        double d = k - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x;
            double v;
            do {
                x = rng.nextGaussian();
                v = 1.0 + c * x;
            } while (v <= 0.0);
            v = v * v * v;
            double u = rng.nextDouble();
            double x2 = x * x;
            if (u < 1.0 - 0.0331 * x2 * x2) {
                return d * v;
            }
            if (Math.log(u) < 0.5 * x2 + d * (1.0 - v + Math.log(v))) {
                return d * v;
            }
        }
    }
}
