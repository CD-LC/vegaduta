package com.agenticai.core.workflow.expression;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Per-evaluation step and wall-clock budget. One instance per public engine call; not shared across
 * threads.
 */
final class Budget {

    private final int maxSteps;
    private final long maxWallNanos;
    private final LongSupplier nanoTime;
    private long startNanos;
    private int steps;

    Budget(Limits limits) {
        this.maxSteps = limits.maxSteps();
        this.maxWallNanos = limits.maxWallNanos();
        this.nanoTime = limits.nanoTime();
        this.startNanos = nanoTime.getAsLong();
    }

    void tick() {
        tick(1);
    }

    void tick(int n) {
        steps += n;
        if (steps > maxSteps || steps < 0) {
            throw ExpressionException.of(
                    "Expression is too complex: more than " + maxSteps + " evaluation steps");
        }
        checkTime();
    }

    /**
     * Runs a context lookup (run-store read, DB) without billing its latency to the wall budget:
     * the cap exists to stop runaway evaluation, not to punish a cold query plan. The step budget
     * is unaffected.
     */
    <T> T io(Supplier<T> lookup) {
        long t0 = nanoTime.getAsLong();
        try {
            return lookup.get();
        } finally {
            startNanos += nanoTime.getAsLong() - t0;
        }
    }

    void checkTime() {
        if (nanoTime.getAsLong() - startNanos > maxWallNanos) {
            throw ExpressionException.of(
                    "Expression took too long (limit " + (maxWallNanos / 1_000_000) + " ms)");
        }
    }

    /**
     * Wraps a string so regex matching checks the deadline as it reads characters; this is what
     * stops catastrophic backtracking, which java.util.regex otherwise cannot be interrupted out
     * of.
     */
    CharSequence guard(CharSequence in) {
        return new Guarded(in, this);
    }

    private static final class Guarded implements CharSequence {
        private final CharSequence in;
        private final Budget budget;
        private int reads;

        Guarded(CharSequence in, Budget budget) {
            this.in = in;
            this.budget = budget;
        }

        @Override
        public int length() {
            return in.length();
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 127) == 0) {
                budget.checkTime();
            }
            return in.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return in.subSequence(start, end);
        }

        @Override
        public String toString() {
            return in.toString();
        }
    }
}
