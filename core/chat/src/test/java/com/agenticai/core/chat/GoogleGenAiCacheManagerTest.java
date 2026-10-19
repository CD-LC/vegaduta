package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.cache.CachedContentRequest;
import org.springframework.ai.google.genai.cache.GoogleGenAiCachedContent;
import org.springframework.ai.google.genai.cache.GoogleGenAiCachedContentService;

/**
 * Content-aware keys and bounded TTL eviction for {@link GoogleGenAiCacheManager}.
 * The previous key was credential+model only (cross-agent system-prompt bleed
 * once wired) and the map never evicted.
 */
class GoogleGenAiCacheManagerTest {

    /** Mutable clock so TTL expiry can be exercised without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-28T00:00:00Z");

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
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final MutableClock clock = new MutableClock();
    private final CachedContentRequest request = mock(CachedContentRequest.class);

    private GoogleGenAiCachedContentService serviceReturning(String name, Instant expiresAt) {
        GoogleGenAiCachedContent created = mock(GoogleGenAiCachedContent.class);
        when(created.getName()).thenReturn(name);
        when(created.getExpireTime()).thenReturn(expiresAt);
        GoogleGenAiCachedContentService service = mock(GoogleGenAiCachedContentService.class);
        when(service.create(any())).thenReturn(created);
        return service;
    }

    // --- key -----------------------------------------------------------------

    @Test
    void differentSystemPromptsOnTheSameCredentialAndModelGetDifferentKeys() {
        String a = GoogleGenAiCacheManager.cacheKey("key-1", "gemini-2.5-flash", "You are agent A", List.of());
        String b = GoogleGenAiCacheManager.cacheKey("key-1", "gemini-2.5-flash", "You are agent B", List.of());
        assertNotEquals(a, b, "sharing a key across system prompts is the cross-agent bleed this fixes");
    }

    @Test
    void toolSchemasAreDistinguishedAndOrderSensitive() {
        String none = GoogleGenAiCacheManager.cacheKey("k", "m", "sys", List.of());
        String one = GoogleGenAiCacheManager.cacheKey("k", "m", "sys", List.of("{\"name\":\"a\"}"));
        String ab = GoogleGenAiCacheManager.cacheKey("k", "m", "sys", List.of("a", "b"));
        String ba = GoogleGenAiCacheManager.cacheKey("k", "m", "sys", List.of("b", "a"));
        assertNotEquals(none, one);
        assertNotEquals(ab, ba);
    }

    @Test
    void credentialModelAndBoundariesAllMatter() {
        String base = GoogleGenAiCacheManager.cacheKey("k1", "m", "sys", null);
        assertNotEquals(base, GoogleGenAiCacheManager.cacheKey("k2", "m", "sys", null));
        assertNotEquals(base, GoogleGenAiCacheManager.cacheKey("k1", "m2", "sys", null));
        // Length-prefixing: shifting a boundary between fields changes the key.
        assertNotEquals(
            GoogleGenAiCacheManager.cacheKey("ab", "c", "sys", null),
            GoogleGenAiCacheManager.cacheKey("a", "bc", "sys", null));
        // null and "" are different inputs.
        assertNotEquals(
            GoogleGenAiCacheManager.cacheKey("k", "m", null, null),
            GoogleGenAiCacheManager.cacheKey("k", "m", "", null));
    }

    @Test
    void keyIsDeterministicAndNeverContainsTheRawCredential() {
        String k1 = GoogleGenAiCacheManager.cacheKey("AIzaSecretKey123", "m", "sys", List.of("t"));
        String k2 = GoogleGenAiCacheManager.cacheKey("AIzaSecretKey123", "m", "sys", List.of("t"));
        assertEquals(k1, k2);
        assertFalse(k1.contains("AIzaSecretKey123"));
        assertTrue(k1.startsWith("gcc:"));
    }

    // --- lifecycle -------------------------------------------------------------

    @Test
    void liveEntryIsReusedWithoutASecondCreate() {
        GoogleGenAiCacheManager manager = new GoogleGenAiCacheManager(clock, 10);
        GoogleGenAiCachedContentService service = serviceReturning("cachedContents/1", clock.instant().plus(Duration.ofHours(1)));

        assertEquals("cachedContents/1", manager.getOrCreate(service, "k", request, Duration.ofHours(1)));
        assertEquals("cachedContents/1", manager.getOrCreate(service, "k", request, Duration.ofHours(1)));
        verify(service, times(1)).create(any());
    }

    @Test
    void expiredEntryIsNotServedAndIsRemovedOnLookup() {
        GoogleGenAiCacheManager manager = new GoogleGenAiCacheManager(clock, 10);
        manager.getOrCreate(serviceReturning("cachedContents/1", clock.instant().plus(Duration.ofMinutes(5))),
            "k", request, Duration.ofMinutes(5));

        clock.advance(Duration.ofMinutes(5));

        assertNull(manager.liveCacheName("k"));
        assertEquals(0, manager.size());
    }

    @Test
    void entryIsTreatedAsDeadThirtySecondsBeforeRealExpiry() {
        GoogleGenAiCacheManager manager = new GoogleGenAiCacheManager(clock, 10);
        manager.getOrCreate(serviceReturning("cachedContents/1", clock.instant().plus(Duration.ofMinutes(5))),
            "k", request, Duration.ofMinutes(5));

        clock.advance(Duration.ofMinutes(4).plusSeconds(31));

        assertNull(manager.liveCacheName("k"));
    }

    @Test
    void creationPurgesExpiredEntries() {
        GoogleGenAiCacheManager manager = new GoogleGenAiCacheManager(clock, 10);
        for (int i = 0; i < 5; i++) {
            manager.getOrCreate(serviceReturning("c/" + i, clock.instant().plus(Duration.ofMinutes(1))),
                "old-" + i, request, Duration.ofMinutes(1));
        }
        clock.advance(Duration.ofMinutes(2));

        manager.getOrCreate(serviceReturning("c/new", clock.instant().plus(Duration.ofHours(1))),
            "new", request, Duration.ofHours(1));

        assertEquals(1, manager.size(), "expired entries must not accumulate forever");
    }

    @Test
    void mapIsCappedEvictingTheSoonestToExpire() {
        GoogleGenAiCacheManager manager = new GoogleGenAiCacheManager(clock, 3);
        manager.getOrCreate(serviceReturning("c/a", clock.instant().plus(Duration.ofMinutes(10))), "a", request, Duration.ofMinutes(10));
        manager.getOrCreate(serviceReturning("c/b", clock.instant().plus(Duration.ofMinutes(30))), "b", request, Duration.ofMinutes(30));
        manager.getOrCreate(serviceReturning("c/c", clock.instant().plus(Duration.ofMinutes(20))), "c", request, Duration.ofMinutes(20));

        manager.getOrCreate(serviceReturning("c/d", clock.instant().plus(Duration.ofMinutes(40))), "d", request, Duration.ofMinutes(40));

        assertEquals(3, manager.size());
        assertNull(manager.liveCacheName("a"), "the soonest-expiring entry is the one evicted");
        assertEquals("c/b", manager.liveCacheName("b"));
        assertEquals("c/c", manager.liveCacheName("c"));
        assertEquals("c/d", manager.liveCacheName("d"));
    }

    @Test
    void missingExpireTimeFallsBackToNowPlusTtl() {
        GoogleGenAiCacheManager manager = new GoogleGenAiCacheManager(clock, 10);
        manager.getOrCreate(serviceReturning("c/1", null), "k", request, Duration.ofMinutes(10));

        clock.advance(Duration.ofMinutes(9));
        assertEquals("c/1", manager.liveCacheName("k"));
        clock.advance(Duration.ofMinutes(1));
        assertNull(manager.liveCacheName("k"));
    }

    @Test
    void creationFailureFailsOpen() {
        GoogleGenAiCacheManager manager = new GoogleGenAiCacheManager(clock, 10);
        GoogleGenAiCachedContentService service = mock(GoogleGenAiCachedContentService.class);
        when(service.create(any())).thenThrow(new RuntimeException("quota"));

        assertNull(manager.getOrCreate(service, "k", request, Duration.ofMinutes(10)));
        assertEquals(0, manager.size());
    }
}
