package com.agenticai.core.platform.abuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@code app.abuse.throttle-store=redis}: the marker is written with the
 * throttle's TTL under a tenant-scoped key, reads and admin listing go to
 * Redis while it answers, {@code clear} lifts it globally, and every Redis
 * error falls back to this replica's in-memory marker (fail-open, never thrown).
 */
class AbuseThrottleServiceRedisTest {

    private static final String KEY = "abuse:throttle:t1:u1";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private AbuseThrottleService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = new AbuseThrottleService(mock(AuditLogService.class), redis);
    }

    @Test
    void throttleWritesATenantScopedMarkerWithTheTtl() {
        service.throttle("t1", "u1", "spend spike", Duration.ofMinutes(30), "admin@x");

        verify(values).set(eq(KEY), org.mockito.ArgumentMatchers.endsWith("|spend spike"), eq(Duration.ofMinutes(30)));
    }

    @Test
    void tenantWideMarkerUsesTheWildcardUser() {
        service.throttle("t1", null, "flood", Duration.ofMinutes(5), "admin@x");

        verify(values).set(eq("abuse:throttle:t1:*"), anyString(), eq(Duration.ofMinutes(5)));
    }

    @Test
    void isThrottledIsAnsweredByRedisWhileItIsReachable() {
        when(redis.hasKey(KEY)).thenReturn(true);
        // Nothing was set on THIS replica - another replica's marker is what counts.
        assertTrue(service.isThrottled("t1", "u1"));

        when(redis.hasKey(KEY)).thenReturn(false);
        service.throttle("t1", "u1", "r", Duration.ofMinutes(5), "a");
        assertFalse(service.isThrottled("t1", "u1"), "a clear on another replica removed the Redis key: Redis wins");
    }

    @Test
    void clearDeletesTheRedisMarker() {
        service.clear("t1", "u1", "admin@x");

        verify(redis).delete(KEY);
    }

    @Test
    void alreadyExpiredTtlDeletesInsteadOfWritingANonPositiveTtl() {
        service.throttle("t1", "u1", "old", Duration.ofMillis(-1), "a");

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verify(redis).delete(KEY);
    }

    @Test
    void redisErrorsFallBackToTheInMemoryMarker() {
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
            .when(values).set(anyString(), anyString(), any(Duration.class));

        service.throttle("t1", "u1", "r", Duration.ofMinutes(5), "a");

        assertTrue(service.isThrottled("t1", "u1"), "local marker still applies");
        assertFalse(service.isThrottled("t1", "other"));
        assertEquals(1, service.current().size(), "scan is unmocked/failing -> in-memory listing");
    }

    @Test
    @SuppressWarnings("unchecked")
    void currentListsLiveMarkersFromRedis() {
        long future = System.currentTimeMillis() + 600_000;
        Cursor<String> cursor = mock(Cursor.class);
        Iterator<String> keys = List.of("abuse:throttle:t1:u1", "abuse:throttle:t2:*", "abuse:throttle:t3:gone").iterator();
        when(cursor.hasNext()).thenAnswer(inv -> keys.hasNext());
        when(cursor.next()).thenAnswer(inv -> keys.next());
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(values.get("abuse:throttle:t1:u1")).thenReturn(future + "|flooding");
        when(values.get("abuse:throttle:t2:*")).thenReturn((System.currentTimeMillis() - 1000) + "|stale");
        when(values.get("abuse:throttle:t3:gone")).thenReturn(null);

        List<AbuseThrottleService.ThrottleEntry> entries = service.current();

        assertEquals(1, entries.size());
        assertEquals("t1:u1", entries.get(0).principal());
        assertEquals("flooding", entries.get(0).reason());
        verify(cursor).close();
    }

    @Test
    void memoryModeNeverTouchesRedis() {
        AbuseThrottleService memory = new AbuseThrottleService(mock(AuditLogService.class));

        memory.throttle("t1", "u1", "r", Duration.ofMinutes(5), "a");

        assertTrue(memory.isThrottled("t1", "u1"));
        verify(redis, never()).hasKey(anyString());
    }
}
