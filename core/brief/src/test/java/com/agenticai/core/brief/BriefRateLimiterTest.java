package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class BriefRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        LocalRateLimitFallback.reset();
    }

    @AfterEach
    void tearDown() {
        LocalRateLimitFallback.reset();
    }

    private BriefRateLimiter limiter() {
        return new BriefRateLimiter(redis, 5, 3, 50, 500);
    }

    @Test
    void firstCallOpensBothWindowsAndIsAllowed() {
        when(ops.increment(anyString())).thenReturn(1L);
        assertThatCode(() -> limiter().assertPlanCallAllowed("user-1", TenantPlan.FREE)).doesNotThrowAnyException();
        verify(redis).expire("agentic:brief-plan-rate:min:user-1", Duration.ofMinutes(1));
        verify(redis).expire("agentic:brief-plan-rate:day:user-1", Duration.ofDays(1));
    }

    @Test
    void burstWindowTripsOnTheSixthCallInAMinute() {
        when(ops.increment("agentic:brief-plan-rate:min:user-1")).thenReturn(6L);
        assertThatThrownBy(() -> limiter().assertPlanCallAllowed("user-1", TenantPlan.PREMIUM))
            .isInstanceOf(BriefRateLimitException.class).hasMessageContaining("too quickly");
        // the daily window is not even consulted once the burst cap has said no
        verify(ops, never()).increment("agentic:brief-plan-rate:day:user-1");
    }

    @Test
    void dailyCapDependsOnThePlan() {
        when(ops.increment("agentic:brief-plan-rate:min:user-1")).thenReturn(1L);
        when(ops.increment("agentic:brief-plan-rate:day:user-1")).thenReturn(4L);
        // 4th call of the day: over the FREE cap of 3, within the PREMIUM cap of 50
        assertThatThrownBy(() -> limiter().assertPlanCallAllowed("user-1", TenantPlan.FREE))
            .isInstanceOf(BriefRateLimitException.class).hasMessageContaining("3 brief plans");
        assertThatCode(() -> limiter().assertPlanCallAllowed("user-1", TenantPlan.PREMIUM)).doesNotThrowAnyException();
        assertThat(limiter().dailyCapFor(TenantPlan.FREE)).isEqualTo(3);
        assertThat(limiter().dailyCapFor(TenantPlan.PREMIUM)).isEqualTo(50);
        assertThat(limiter().dailyCapFor(TenantPlan.UNLIMITED)).isEqualTo(500);
        assertThat(limiter().dailyCapFor(null)).isEqualTo(3);
    }

    @Test
    void callersAreCountedSeparately() {
        when(ops.increment("agentic:brief-plan-rate:min:user-1")).thenReturn(6L);
        when(ops.increment("agentic:brief-plan-rate:min:user-2")).thenReturn(1L);
        when(ops.increment("agentic:brief-plan-rate:day:user-2")).thenReturn(1L);
        assertThatThrownBy(() -> limiter().assertPlanCallAllowed("user-1", TenantPlan.FREE)).isInstanceOf(BriefRateLimitException.class);
        assertThatCode(() -> limiter().assertPlanCallAllowed("user-2", TenantPlan.FREE)).doesNotThrowAnyException();
    }

    @Test
    void redisFailureDegradesToTheInProcessLimiterInsteadOfFailingOpen() {
        when(ops.increment(anyString())).thenThrow(new RuntimeException("redis down"));
        String subject = "user-" + UUID.randomUUID();
        BriefRateLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) { // FREE: 3 per day and 5 per minute, so the daily window is the binding one
            limiter.assertPlanCallAllowed(subject, TenantPlan.FREE);
        }
        assertThatThrownBy(() -> limiter.assertPlanCallAllowed(subject, TenantPlan.FREE))
            .isInstanceOf(BriefRateLimitException.class);
    }

    @Test
    void anonymousCallerIsNotMetered() {
        assertThatCode(() -> limiter().assertPlanCallAllowed(null, TenantPlan.FREE)).doesNotThrowAnyException();
        assertThatCode(() -> limiter().assertPlanCallAllowed(" ", TenantPlan.FREE)).doesNotThrowAnyException();
        verify(ops, never()).increment(anyString());
    }
}
