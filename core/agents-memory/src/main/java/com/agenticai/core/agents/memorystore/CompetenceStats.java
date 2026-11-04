package com.agenticai.core.agents.memorystore;

/**
 * Wilson score lower bound (95%, two-sided). A small local copy: {@code core/quality} has the same formula but pulls in
 * the chat and provider stacks, which this memory module must not depend on. Tested against the same reference values.
 */
final class CompetenceStats {

    /** z for 95% two-sided coverage. */
    static final double Z95 = 1.959963984540054;

    private CompetenceStats() {}

    static double wilsonLowerBound(long successes, long n) {
        if (n <= 0) {
            return 0.0;
        }
        long s = Math.max(0, Math.min(successes, n));
        double p = (double) s / n;
        double z2 = Z95 * Z95;
        double centre = p + z2 / (2.0 * n);
        double half = Z95 * Math.sqrt(p * (1.0 - p) / n + z2 / (4.0 * n * (double) n));
        double lower = (centre - half) / (1.0 + z2 / n);
        return Math.max(0.0, Math.min(1.0, lower));
    }
}
