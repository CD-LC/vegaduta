package com.agenticai.core.providers.localmodels;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The fixed per-caller, per-minute windows on the local-models API. Every
 * test uses a unique caller id because {@link
 * com.agenticai.core.security.ratelimit.LocalRateLimitFallback} keeps its
 * counters in process-wide static state - a shared caller would leak counts
 * between tests.
 */
class LocalModelRateLimiterTest {

    private static String caller() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void callsWithinTheCapPass() {
        LocalModelRateLimiter limiter = new LocalModelRateLimiter(100, 2, 2);
        String caller = caller();
        assertDoesNotThrow(() -> limiter.checkRead(caller));
        assertDoesNotThrow(() -> limiter.checkProbe(caller));
        assertDoesNotThrow(() -> limiter.checkPull(caller));
    }

    @Test
    void theNPlusOnethProbeIsA429() {
        LocalModelRateLimiter limiter = new LocalModelRateLimiter(100, 2, 50);
        String caller = caller();
        limiter.checkProbe(caller);
        limiter.checkProbe(caller);
        ResponseStatusException e =
            assertThrows(ResponseStatusException.class, () -> limiter.checkProbe(caller));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
    }

    @Test
    void pullsAndReadsHaveTheirOwnSeparateBuckets() {
        LocalModelRateLimiter limiter = new LocalModelRateLimiter(1, 50, 1);
        String caller = caller();
        limiter.checkPull(caller);
        assertThrows(ResponseStatusException.class, () -> limiter.checkPull(caller));
        // The pull cap being hit says nothing about the read bucket.
        assertDoesNotThrow(() -> limiter.checkRead(caller));
        assertThrows(ResponseStatusException.class, () -> limiter.checkRead(caller));
    }

    @Test
    void differentCallersDoNotShareABucket() {
        LocalModelRateLimiter limiter = new LocalModelRateLimiter(100, 1, 100);
        limiter.checkProbe(caller());
        // A different caller still has its own allowance.
        assertDoesNotThrow(() -> limiter.checkProbe(caller()));
    }
}
