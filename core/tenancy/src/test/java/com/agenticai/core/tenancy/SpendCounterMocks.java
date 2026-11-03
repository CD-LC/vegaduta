package com.agenticai.core.tenancy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.SpendCounters;
import java.util.List;
import java.util.function.LongFunction;
import org.mockito.ArgumentMatcher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Mock-side view of {@link SpendCounters}: stubs and verifies the atomic add/refund scripts on a mocked template.
 * Whether the scripts are CORRECT is proven against a real Redis in the *RedisTest classes.
 */
final class SpendCounterMocks {

    private SpendCounterMocks() {
    }

    private static ArgumentMatcher<List<String>> keyContains(String part) {
        return keys -> keys != null && keys.size() == 1 && keys.get(0).contains(part);
    }

    /** {@code by >= 0}: the add script; {@code by < 0}: the refund script. */
    @SuppressWarnings("unchecked")
    static void stub(StringRedisTemplate t, String keyPart, long by, Long result) {
        if (by < 0) {
            when(t.execute(eq(SpendCounters.REFUND_IF_LIVE), argThat(keyContains(keyPart)), eq(Long.toString(by))))
                .thenReturn(result);
        } else {
            when(t.execute(eq(SpendCounters.INCR_WITH_TTL), argThat(keyContains(keyPart)), eq(Long.toString(by)),
                anyString())).thenReturn(result);
        }
    }

    static void stub(StringRedisTemplate t, long by, Long result) {
        stub(t, "", by, result);
    }

    /** Every add or refund answers with {@code f(by)}. */
    @SuppressWarnings("unchecked")
    static void stubAll(StringRedisTemplate t, LongFunction<Long> f) {
        when(t.execute(any(RedisScript.class), any(List.class), any(Object[].class))).thenAnswer(
            inv -> f.apply(Long.parseLong(inv.getArgument(2))));
    }

    /** Real add/refund semantics over an in-memory map (refund drops an absent key and clamps at zero). */
    @SuppressWarnings("unchecked")
    static void stubInMemory(StringRedisTemplate t, java.util.concurrent.ConcurrentMap<String, java.util.concurrent.atomic.AtomicLong> m) {
        when(t.execute(any(RedisScript.class), any(List.class), any(Object[].class))).thenAnswer(inv -> {
            RedisScript<?> script = inv.getArgument(0);
            String key = ((List<String>) inv.getArgument(1)).get(0);
            long by = Long.parseLong(inv.getArgument(2));
            if (script == SpendCounters.REFUND_IF_LIVE) {
                java.util.concurrent.atomic.AtomicLong v = m.get(key);
                return v == null ? Long.valueOf(0L) : Long.valueOf(v.updateAndGet(x -> Math.max(0L, x + by)));
            }
            return m.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong()).addAndGet(by);
        });
    }

    @SuppressWarnings("unchecked")
    static void stubAllThrow(StringRedisTemplate t, RuntimeException e) {
        when(t.execute(any(RedisScript.class), any(List.class), any(Object[].class))).thenThrow(e);
    }

    @SuppressWarnings("unchecked")
    static void verifyAdded(StringRedisTemplate t, String keyPart, long by) {
        if (by < 0) {
            verify(t).execute(eq(SpendCounters.REFUND_IF_LIVE), argThat(keyContains(keyPart)), eq(Long.toString(by)));
        } else {
            verify(t).execute(eq(SpendCounters.INCR_WITH_TTL), argThat(keyContains(keyPart)), eq(Long.toString(by)),
                anyString());
        }
    }

    static void verifyAdded(StringRedisTemplate t, long by) {
        verifyAdded(t, "", by);
    }

    @SuppressWarnings("unchecked")
    static void verifyNothingAdded(StringRedisTemplate t) {
        verify(t, never()).execute(any(RedisScript.class), any(List.class), any(Object[].class));
    }
}
