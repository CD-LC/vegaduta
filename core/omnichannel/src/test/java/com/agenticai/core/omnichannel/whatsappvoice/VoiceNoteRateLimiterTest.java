package com.agenticai.core.omnichannel.whatsappvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class VoiceNoteRateLimiterTest {

    /** A clock the test can move; no sleeping. */
    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_000_000L);

        void advanceMinutes(long minutes) {
            millis.addAndGet(minutes * 60_000L);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    @Test
    void allowsUpToTheLimitThenDeniesAndNotifiesOnlyOnce() {
        MutableClock clock = new MutableClock();
        VoiceNoteRateLimiter limiter = new VoiceNoteRateLimiter(2, clock);

        assertEquals(VoiceNoteRateLimiter.Decision.ALLOW, limiter.tryAcquire("a"));
        assertEquals(VoiceNoteRateLimiter.Decision.ALLOW, limiter.tryAcquire("a"));
        assertEquals(VoiceNoteRateLimiter.Decision.DENY_FIRST, limiter.tryAcquire("a"));
        assertEquals(VoiceNoteRateLimiter.Decision.DENY_REPEAT, limiter.tryAcquire("a"));
        assertEquals(VoiceNoteRateLimiter.Decision.DENY_REPEAT, limiter.tryAcquire("a"));
    }

    @Test
    void sendersAreIndependent() {
        VoiceNoteRateLimiter limiter = new VoiceNoteRateLimiter(1, new MutableClock());

        assertEquals(VoiceNoteRateLimiter.Decision.ALLOW, limiter.tryAcquire("a"));
        assertEquals(VoiceNoteRateLimiter.Decision.DENY_FIRST, limiter.tryAcquire("a"));
        assertEquals(VoiceNoteRateLimiter.Decision.ALLOW, limiter.tryAcquire("b"));
    }

    @Test
    void windowSlidesAfterAnHourAndTheNoticeIsRearmed() {
        MutableClock clock = new MutableClock();
        VoiceNoteRateLimiter limiter = new VoiceNoteRateLimiter(1, clock);

        assertEquals(VoiceNoteRateLimiter.Decision.ALLOW, limiter.tryAcquire("a"));
        assertEquals(VoiceNoteRateLimiter.Decision.DENY_FIRST, limiter.tryAcquire("a"));

        clock.advanceMinutes(61);

        assertEquals(VoiceNoteRateLimiter.Decision.ALLOW, limiter.tryAcquire("a"));
        assertEquals(VoiceNoteRateLimiter.Decision.DENY_FIRST, limiter.tryAcquire("a"));
    }

    @Test
    void zeroOrNegativeLimitMeansUnlimited() {
        VoiceNoteRateLimiter limiter = new VoiceNoteRateLimiter(0, new MutableClock());

        for (int i = 0; i < 100; i++) {
            assertEquals(VoiceNoteRateLimiter.Decision.ALLOW, limiter.tryAcquire("a"));
        }
    }
}
