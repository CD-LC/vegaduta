package com.agenticai.core.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** Test clock that only moves when told to. */
final class ManualClock extends Clock {

    private final AtomicLong millis = new AtomicLong(1_000_000L);

    void advance(Duration d) {
        millis.addAndGet(d.toMillis());
    }

    void advanceMillis(long ms) {
        millis.addAndGet(ms);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public long millis() {
        return millis.get();
    }
}
