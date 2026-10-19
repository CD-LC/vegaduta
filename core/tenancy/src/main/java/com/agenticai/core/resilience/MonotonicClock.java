package com.agenticai.core.resilience;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

/**
 * A {@link Clock} whose {@link #millis()} is monotonic ({@link System#nanoTime()}), for measuring
 * ELAPSED time in the breaker: an NTP step or a manual wall-clock change must neither keep an open
 * circuit open far longer than {@code open-duration} (clock stepped back) nor expire it early (stepped
 * forward). The absolute value of {@code millis()} is meaningless (arbitrary origin, may be negative);
 * only differences between two readings are, which is the only way the resilience package uses it.
 * {@link #instant()} is the wall clock and exists only to satisfy the {@code Clock} contract.
 */
final class MonotonicClock extends Clock {

    static final MonotonicClock INSTANCE = new MonotonicClock();

    private MonotonicClock() {
    }

    @Override
    public long millis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    @Override
    public Instant instant() {
        return Instant.now();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
