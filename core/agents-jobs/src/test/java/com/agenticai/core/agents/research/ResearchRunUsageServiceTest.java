package com.agenticai.core.agents.research;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.web.DeepResearchDailyAllowance;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The per-user daily cap: counts, refuses with 429 at the cap, gives slots back, and fails open.
 */
class ResearchRunUsageServiceTest {

    private static final String KEY = ResearchRunUsageService.KEY_PREFIX + "t1:alice";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ObjectProvider<StringRedisTemplate> provider;

    static ResearchRunProperties properties(boolean runsEnabled, boolean deepEnabled, int perDay) {
        return new ResearchRunProperties(
                runsEnabled, perDay, 600, deepEnabled, 12, 12, 8, 120, new BigDecimal("2.0"));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
    }

    @Test
    void firstRunOfTheWindowIsCountedAndStartsTheExpiry() {
        when(values.increment(KEY, 1L)).thenReturn(1L);
        new ResearchRunUsageService(provider, properties(true, true, 5)).reserve("t1", "alice");
        verify(redis).expire(KEY, Duration.ofDays(1));
    }

    @Test
    void aLaterRunDoesNotResetTheWindow() {
        when(values.increment(KEY, 1L)).thenReturn(3L);
        new ResearchRunUsageService(provider, properties(true, true, 5)).reserve("t1", "alice");
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void refusesWith429PastTheCapAndGivesTheSlotBack() {
        when(values.increment(KEY, 1L)).thenReturn(6L);
        when(values.increment(KEY, -1L)).thenReturn(5L);
        ResponseStatusException refused =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                new ResearchRunUsageService(provider, properties(true, true, 5))
                                        .reserve("t1", "alice"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), refused.getStatusCode().value());
        verify(values).increment(KEY, -1L);
    }

    @Test
    void aLostExpiryIsRepairedByTheNextRunSoTheCounterCannotStickForever() {
        when(values.increment(KEY, 1L)).thenReturn(3L);
        when(redis.getExpire(KEY)).thenReturn(-1L);
        new ResearchRunUsageService(provider, properties(true, true, 5)).reserve("t1", "alice");
        verify(redis).expire(KEY, Duration.ofDays(1));
    }

    @Test
    void aFailedExpiryStillCountsTheRunInsteadOfFailingOpenWithAnImmortalKey() {
        when(values.increment(KEY, 1L)).thenReturn(1L);
        when(redis.expire(KEY, Duration.ofDays(1)))
                .thenThrow(new IllegalStateException("redis blip"));
        assertDoesNotThrow(
                () ->
                        new ResearchRunUsageService(provider, properties(true, true, 5))
                                .reserve("t1", "alice"));
        // The next write sees the key without a time-to-live and sets it (previous test).
    }

    @Test
    void releaseDoesNotRecreateAnExpiredWindowBelowZero() {
        ResearchRunUsageService service =
                new ResearchRunUsageService(provider, properties(true, true, 5));
        when(redis.hasKey(KEY)).thenReturn(false);
        service.release("t1", "alice");
        verify(values, never()).increment(anyString(), anyLong());

        when(redis.hasKey(KEY)).thenReturn(true);
        service.release("t1", "alice");
        verify(values).increment(KEY, -1L);
    }

    @Test
    void aSpentSearchAllowanceRefusesTheRunAndShowsZeroLeftWithoutCountingARun() {
        DeepResearchDailyAllowance searches = mock(DeepResearchDailyAllowance.class);
        when(searches.remaining("t1", "alice")).thenReturn(0);
        ResearchRunUsageService service =
                new ResearchRunUsageService(provider, properties(true, true, 5), searches);
        assertEquals(Integer.valueOf(0), service.remainingToday("t1", "alice"));
        ResponseStatusException refused =
                assertThrows(ResponseStatusException.class, () -> service.reserve("t1", "alice"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), refused.getStatusCode().value());
        verify(values, never()).increment(anyString(), anyLong());

        when(searches.remaining("t1", "alice")).thenReturn(7);
        when(values.get(KEY)).thenReturn("1");
        assertEquals(Integer.valueOf(4), service.remainingToday("t1", "alice"));
    }

    @Test
    void remainingIsReadOnlyAndNeverNegative() {
        ResearchRunUsageService service =
                new ResearchRunUsageService(provider, properties(true, true, 5));
        when(values.get(KEY)).thenReturn(null);
        assertEquals(Integer.valueOf(5), service.remainingToday("t1", "alice"));
        when(values.get(KEY)).thenReturn("2");
        assertEquals(Integer.valueOf(3), service.remainingToday("t1", "alice"));
        when(values.get(KEY)).thenReturn("9");
        assertEquals(Integer.valueOf(0), service.remainingToday("t1", "alice"));
        when(values.get(KEY)).thenReturn("garbage");
        assertEquals(Integer.valueOf(5), service.remainingToday("t1", "alice"));
        verify(values, never()).increment(anyString(), anyLong());
    }

    @Test
    void zeroCapMeansNoLimitAndNoRedisTraffic() {
        ResearchRunUsageService service =
                new ResearchRunUsageService(provider, properties(true, true, 0));
        assertNull(service.remainingToday("t1", "alice"));
        service.reserve("t1", "alice");
        service.release("t1", "alice");
        verify(redis, never()).opsForValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisDownOrAbsentFallsBackToAnInProcessCapInsteadOfNoCap() {
        when(values.increment(anyString(), anyLong()))
                .thenThrow(new IllegalStateException("redis down"));
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        ResearchRunUsageService down =
                new ResearchRunUsageService(provider, properties(true, true, 5));
        assertDoesNotThrow(() -> down.reserve("t1", "alice"));
        assertEquals(Integer.valueOf(4), down.remainingToday("t1", "alice"));
        assertDoesNotThrow(() -> down.release("t1", "alice"));
        assertEquals(Integer.valueOf(5), down.remainingToday("t1", "alice"));
        for (int i = 0; i < 5; i++) {
            down.reserve("t1", "alice");
        }
        // The sixth run of the window is refused even though Redis is unreachable.
        assertEquals(
                429,
                assertThrows(ResponseStatusException.class, () -> down.reserve("t1", "alice"))
                        .getStatusCode()
                        .value());
        assertEquals(Integer.valueOf(0), down.remainingToday("t1", "alice"));

        ObjectProvider<StringRedisTemplate> none = mock(ObjectProvider.class);
        when(none.getIfAvailable()).thenReturn(null);
        ResearchRunUsageService absent =
                new ResearchRunUsageService(none, properties(true, true, 5));
        assertDoesNotThrow(() -> absent.reserve("t1", "alice"));
        assertEquals(Integer.valueOf(4), absent.remainingToday("t1", "alice"));
    }
}
