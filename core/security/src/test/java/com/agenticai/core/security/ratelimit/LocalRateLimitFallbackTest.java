package com.agenticai.core.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The degraded-mode limiter every Redis-backed limiter falls back to. What
 * matters is that it actually LIMITS (the behaviour it replaced was "return
 * true"), keeps keys apart, and forgets a window once it has passed.
 */
class LocalRateLimitFallbackTest {

    @BeforeEach
    void setUp() {
        LocalRateLimitFallback.reset();
    }

    @Test
    void itAllowsUpToTheLimitAndThenRefuses() {
        for (int i = 0; i < 5; i++) {
            assertTrue(LocalRateLimitFallback.tryAcquire("signup:1.2.3.4", 5, Duration.ofMinutes(1)));
        }
        assertFalse(LocalRateLimitFallback.tryAcquire("signup:1.2.3.4", 5, Duration.ofMinutes(1)));
    }

    @Test
    void oneCallersExhaustionDoesNotAffectAnother() {
        assertTrue(LocalRateLimitFallback.tryAcquire("a", 1, Duration.ofMinutes(1)));
        assertFalse(LocalRateLimitFallback.tryAcquire("a", 1, Duration.ofMinutes(1)));
        assertTrue(LocalRateLimitFallback.tryAcquire("b", 1, Duration.ofMinutes(1)));
    }

    @Test
    void aNewWindowStartsOnceTheOldOneHasPassed() throws InterruptedException {
        assertTrue(LocalRateLimitFallback.tryAcquire("k", 1, Duration.ofMillis(40)));
        assertFalse(LocalRateLimitFallback.tryAcquire("k", 1, Duration.ofMillis(40)));
        Thread.sleep(80);
        assertTrue(LocalRateLimitFallback.tryAcquire("k", 1, Duration.ofMillis(40)));
    }
}
