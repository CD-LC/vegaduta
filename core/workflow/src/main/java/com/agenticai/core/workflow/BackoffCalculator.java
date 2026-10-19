package com.agenticai.core.workflow;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/**
 * Retry backoff for the in-process engine: exponential (base 500 ms, doubling) capped at 10 s, with full
 * jitter. The cap progression ({@link #capMillis}) is a pure function of the attempt number; only
 * {@link #jitteredDelayMillis(int)} draws randomness, and it is used solely by the in-process engine (the
 * Temporal workflow delegates backoff to the server's {@code RetryOptions}, so no randomness ever reaches
 * workflow code).
 */
final class BackoffCalculator {

    private static final long BASE_DELAY_MILLIS = 500;
    private static final long MAX_DELAY_MILLIS = 10_000;

    private BackoffCalculator() {}

    /**
     * Upper bound of the sleep after failed attempt {@code attempt}: 500, 1000, 2000, ... capped at 10 s. A
     * non-positive attempt degrades to the first attempt's bound.
     */
    static long capMillis(int attempt) {
        // Exponent clamp is an overflow guard only - 500 << 20 is already far past the cap.
        int exponent = Math.min(Math.max(attempt, 1) - 1, 20);
        return Math.min(BASE_DELAY_MILLIS << exponent, MAX_DELAY_MILLIS);
    }

    /**
     * Full jitter: uniform in {@code [0, capMillis(attempt)]}, so synchronized failures (Parallel branches,
     * concurrent runs against one rate-limited provider) spread over the whole window.
     *
     * @param uniformBelow returns a value uniform in {@code [0, bound)}; injectable so tests need no sleeping
     */
    static long jitteredDelayMillis(int attempt, LongUnaryOperator uniformBelow) {
        return uniformBelow.applyAsLong(capMillis(attempt) + 1);
    }

    static long jitteredDelayMillis(int attempt) {
        return jitteredDelayMillis(attempt, bound -> ThreadLocalRandom.current().nextLong(bound));
    }
}
