package com.agenticai.core.quality.memeval;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A hand-wound UTC clock for one memory-eval execution. The runner advances it by each session's
 * scripted {@code gap_days} before the session is played, so memory decay, last-used stamps and the
 * daily review (which read "now" from the platform's named {@code memoryClock} bean, see {@code
 * MemoryClockConfiguration} in agents-turn) behave as if the days had passed, without sleeping.
 *
 * <p>Not thread-safe by design: one execution replays its sessions on one thread, in order.
 */
public final class EvalClock extends Clock {

    /** Day zero of every execution; fixed so two runs of the same case see the same instants. */
    public static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private Instant now;

    public EvalClock() {
        this(START);
    }

    public EvalClock(Instant start) {
        this.now = java.util.Objects.requireNonNull(start);
    }

    /**
     * Moves time forward; a negative or zero advance is rejected (time in the eval only moves on).
     */
    public void advanceDays(int days) {
        if (days < 0) {
            throw new IllegalArgumentException("a clock cannot go back, got " + days + " days");
        }
        now = now.plus(Duration.ofDays(days));
    }

    /** Whole days elapsed since {@link #START}. */
    public long elapsedDays() {
        return Duration.between(START, now).toDays();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (ZoneOffset.UTC.equals(zone)) {
            return this;
        }
        throw new UnsupportedOperationException("EvalClock is UTC only");
    }

    @Override
    public Instant instant() {
        return now;
    }
}
