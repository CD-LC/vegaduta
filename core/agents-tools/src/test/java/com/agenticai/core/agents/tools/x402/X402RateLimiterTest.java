package com.agenticai.core.agents.tools.x402;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Sliding-window semantics on a controllable clock. */
class X402RateLimiterTest {

    /** Clock whose instant the test advances by hand. */
    private static final class MutableClock extends Clock {
        private final AtomicLong millis =
                new AtomicLong(Instant.parse("2026-10-02T12:00:00Z").toEpochMilli());

        void advance(long ms) {
            millis.addAndGet(ms);
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Test
    void allowsUpToTheLimitThenRejects() {
        X402RateLimiter limiter = new X402RateLimiter(3, new MutableClock());
        assertTrue(limiter.tryAcquire("1.2.3.4"));
        assertTrue(limiter.tryAcquire("1.2.3.4"));
        assertTrue(limiter.tryAcquire("1.2.3.4"));
        assertFalse(limiter.tryAcquire("1.2.3.4"));
    }

    @Test
    void differentClientsHaveIndependentBudgets() {
        X402RateLimiter limiter = new X402RateLimiter(2, new MutableClock());
        assertTrue(limiter.tryAcquire("1.2.3.4"));
        assertTrue(limiter.tryAcquire("1.2.3.4"));
        assertFalse(limiter.tryAcquire("1.2.3.4"));
        assertTrue(limiter.tryAcquire("5.6.7.8"));
    }

    @Test
    void windowSlidesSoOldHitsStopCounting() {
        MutableClock clock = new MutableClock();
        X402RateLimiter limiter = new X402RateLimiter(2, clock);
        assertTrue(limiter.tryAcquire("1.2.3.4"));
        assertTrue(limiter.tryAcquire("1.2.3.4"));
        assertFalse(limiter.tryAcquire("1.2.3.4"));
        clock.advance(61_000); // whole window elapsed
        assertTrue(limiter.tryAcquire("1.2.3.4"));
    }

    @Test
    void zeroDisablesTheLimiter() {
        X402RateLimiter limiter = new X402RateLimiter(0, new MutableClock());
        for (int i = 0; i < 500; i++) {
            assertTrue(limiter.tryAcquire("1.2.3.4"));
        }
    }
}
