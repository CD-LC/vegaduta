package com.agenticai.core.payments;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The revocation tombstone is fail-soft: the DB row is authoritative, so a
 * missing Redis bean or a Redis error degrades to "the cache says nothing"
 * rather than an exception - and a tombstone write failure is logged, never
 * thrown, for the same reason.
 */
class MandateRevocationCacheTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> providerOf(StringRedisTemplate template) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> emptyProvider() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Test
    void noRedisBeanMeansNothingIsRevokedAndTombstoneIsANoOp() {
        MandateRevocationCache cache = new MandateRevocationCache(emptyProvider());
        UUID id = UUID.randomUUID();
        assertFalse(cache.isRevoked(id));
        assertDoesNotThrow(() -> cache.tombstone(id));
    }

    @Test
    void tombstoneWritesTheRevokedKeyWithTheThirtyDayTtl() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        MandateRevocationCache cache = new MandateRevocationCache(providerOf(template));

        UUID id = UUID.randomUUID();
        cache.tombstone(id);
        verify(ops).set(MandateRevocationCache.KEY_PREFIX + id, "1", Duration.ofDays(30));
    }

    @Test
    void isRevokedMirrorsTheStoredKey() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        UUID id = UUID.randomUUID();
        when(template.hasKey(MandateRevocationCache.KEY_PREFIX + id)).thenReturn(true);
        MandateRevocationCache cache = new MandateRevocationCache(providerOf(template));
        assertTrue(cache.isRevoked(id));
        assertFalse(cache.isRevoked(UUID.randomUUID()), "an unknown id answers false");
    }

    @Test
    void aRedisBlipFailsSoftOnBothSides() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.hasKey(anyString())).thenThrow(new RuntimeException("connection reset"));
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        org.mockito.Mockito.doThrow(new RuntimeException("connection reset"))
            .when(ops).set(anyString(), anyString(), any(Duration.class));
        MandateRevocationCache cache = new MandateRevocationCache(providerOf(template));

        UUID id = UUID.randomUUID();
        assertFalse(cache.isRevoked(id), "a Redis error reads as not-revoked, not as an exception");
        assertDoesNotThrow(() -> cache.tombstone(id));
        verify(template, never()).hasKey(eq("something-else"));
    }
}
