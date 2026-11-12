package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agenticai.core.agents.a2a.A2aInboundGuardTest.MutableClock;
import com.agenticai.core.agents.a2a.A2aNonceCache.Claim;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class A2aNonceCacheTest {

    private MutableClock clock;
    private A2aNonceCache cache;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-28T12:00:00Z"));
        cache = new A2aNonceCache(Duration.ofMinutes(10), 3, clock, null);
    }

    @Test
    void secondSightingInsideTheTtlIsAReplay() {
        assertEquals(Claim.FRESH, cache.claim("t1", "kid", "n1"));
        assertEquals(Claim.REPLAYED, cache.claim("t1", "kid", "n1"));
    }

    @Test
    void nonceExpiresAfterTheTtl() {
        cache.claim("t1", "kid", "n1");
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertEquals(Claim.FRESH, cache.claim("t1", "kid", "n1"));
    }

    @Test
    void nonceIsScopedPerTenantAndPerKey() {
        cache.claim("t1", "kid", "n1");
        assertEquals(Claim.FRESH, cache.claim("t2", "kid", "n1"));
        assertEquals(Claim.FRESH, cache.claim("t1", "other-kid", "n1"));
    }

    @Test
    void aFullCacheRefusesRatherThanEvictingALiveNonce() {
        cache.claim("t1", "kid", "a");
        cache.claim("t1", "kid", "b");
        cache.claim("t1", "kid", "c");

        assertEquals(Claim.FULL, cache.claim("t1", "kid", "d"));
        // The oldest nonce is still protected: it was not evicted to make room.
        assertEquals(Claim.REPLAYED, cache.claim("t1", "kid", "a"));
        // A different tenant has its own budget.
        assertEquals(Claim.FRESH, cache.claim("t2", "kid", "d"));
    }

    @Test
    void expiredNonceMakesRoom() {
        cache.claim("t1", "kid", "a");
        clock.advance(Duration.ofMinutes(6));
        cache.claim("t1", "kid", "b");
        cache.claim("t1", "kid", "c");
        clock.advance(Duration.ofMinutes(5)); // "a" is now past its TTL, b and c are not
        assertEquals(Claim.FRESH, cache.claim("t1", "kid", "d"));
        assertEquals(3, cache.size("t1"));
    }
}
