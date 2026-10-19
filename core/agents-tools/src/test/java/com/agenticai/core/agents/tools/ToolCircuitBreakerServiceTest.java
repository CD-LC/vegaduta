package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.gateway.GovernanceDegradationSignal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Governance fail-open observability (2026-08-07): {@link ToolCircuitBreakerService} has always
 * degraded open when Redis is unreachable - the right availability call per its class javadoc, but
 * two of its three fail-open branches were {@code catch (RuntimeException ignored)} blocks with no
 * trace at all. This suite pins that every fail-open branch now reports through {@link
 * GovernanceDegradationSignal} (so a Redis blip that silently disables the breaker platform-wide is
 * alertable), while the fail-open BEHAVIOR itself - never throw, treat as not-open - is unchanged.
 */
class ToolCircuitBreakerServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private SimpleMeterRegistry meterRegistry;
    private ToolCircuitBreakerService breaker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        meterRegistry = new SimpleMeterRegistry();
        breaker =
                new ToolCircuitBreakerService(
                        redisTemplate, new GovernanceDegradationSignal(meterRegistry), 5, 5, 10);
    }

    private double failOpenCount() {
        return meterRegistry
                .counter(
                        GovernanceDegradationSignal.METRIC_NAME,
                        "component",
                        "tool-circuit-breaker")
                .count();
    }

    @Test
    void isOpenFailsOpenAndReportsTheDegradation() {
        when(redisTemplate.hasKey(anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertFalse(
                breaker.isOpen("some_tool"),
                "behavior unchanged: unreachable Redis means not-open");
        assertEquals(1.0, failOpenCount());
    }

    @Test
    void recordFailureFailsOpenAndReportsTheDegradation() {
        when(valueOperations.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertDoesNotThrow(() -> breaker.recordFailure("some_tool"));
        assertEquals(1.0, failOpenCount());
    }

    @Test
    void recordSuccessFailsOpenAndReportsTheDegradation() {
        when(redisTemplate.delete(anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertDoesNotThrow(() -> breaker.recordSuccess("some_tool"));
        assertEquals(1.0, failOpenCount());
    }

    @Test
    void healthyRedisReportsNoDegradation() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(redisTemplate.delete(anyString())).thenReturn(true);

        breaker.isOpen("some_tool");
        breaker.recordFailure("some_tool");
        breaker.recordSuccess("some_tool");

        assertEquals(0.0, failOpenCount());
    }

    @Test
    void tripStoresLastErrorAlongsideTheOpenMarker() {
        when(valueOperations.increment(anyString())).thenReturn(5L);

        breaker.recordFailure("some_tool", "connection refused");

        verify(valueOperations)
                .set(
                        argThat(k -> k.startsWith("agentic:tool-circuit:open:")),
                        eq("1"),
                        eq(Duration.ofMinutes(10)));
        verify(valueOperations)
                .set(
                        argThat(k -> k.startsWith("agentic:tool-circuit:lasterror:")),
                        eq("connection refused"),
                        eq(Duration.ofMinutes(10)));
        verify(redisTemplate)
                .delete(argThat((String k) -> k.startsWith("agentic:tool-circuit:failures:")));
    }

    @Test
    void openInfoIsNullWhenClosedAndCarriesTtlAndErrorWhenOpen() {
        when(redisTemplate.getExpire(anyString())).thenReturn(null);
        assertNull(breaker.openInfo("some_tool"));

        String tenant =
                com.agenticai.core.security.TenantContext.current()
                        .map(com.agenticai.core.security.TenantContext::getTenantId)
                        .orElse("__no-tenant__");
        when(redisTemplate.getExpire("agentic:tool-circuit:open:" + tenant + ":some_tool"))
                .thenReturn(600L);
        when(valueOperations.get("agentic:tool-circuit:lasterror:" + tenant + ":some_tool"))
                .thenReturn("boom");

        ToolCircuitBreakerService.OpenInfo info = breaker.openInfo("some_tool");
        assertEquals(600L, info.secondsRemaining());
        assertEquals("boom", info.lastError());
    }

    @Test
    void openInfoFailsOpenAndReportsTheDegradation() {
        when(redisTemplate.getExpire(anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertNull(breaker.openInfo("some_tool"));
        assertEquals(1.0, failOpenCount());
    }
}
